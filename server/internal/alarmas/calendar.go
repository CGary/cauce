package alarmas

import (
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
)

// calendario generates the firing instants of one alarm in its time zone. The
// first firing is the first occurrence of `hora` strictly after the creation
// ts (alarma_creada.ts); the RRULE subset repeats from there. `count` and
// `until` end the sequence, months without the requested day are skipped and
// do not consume `count` (RFC 5545).
type calendario struct {
	loc    *time.Location
	hour   int
	min    int
	anchor time.Time
	rep    *repeticion
}

func newCalendario(hora string, rep *repeticion, anchor time.Time, loc *time.Location) (*calendario, error) {
	hour, min, err := parseHora(hora)
	if err != nil {
		return nil, err
	}
	return &calendario{loc: loc, hour: hour, min: min, anchor: anchor, rep: rep}, nil
}

func parseHora(hora string) (int, int, error) {
	parts := strings.Split(hora, ":")
	if len(parts) != 2 {
		return 0, 0, fmt.Errorf("hora %q: want HH:MM", hora)
	}
	hour, err := strconv.Atoi(parts[0])
	if err != nil || hour < 0 || hour > 23 {
		return 0, 0, fmt.Errorf("hora %q: invalid hour", hora)
	}
	min, err := strconv.Atoi(parts[1])
	if err != nil || min < 0 || min > 59 {
		return 0, 0, fmt.Errorf("hora %q: invalid minute", hora)
	}
	return hour, min, nil
}

// eachFiring calls fn for every firing in chronological order, stopping when
// fn returns false or when count/until ends the sequence.
func (c *calendario) eachFiring(fn func(time.Time) bool) {
	next := c.sequence()
	for emitted := 0; ; emitted++ {
		t, ok := next()
		if !ok {
			return
		}
		if !c.allowed(t, emitted) {
			return
		}
		if !fn(t) {
			return
		}
	}
}

// allowed applies count (first firing included) and until (local date,
// inclusive). Skipped months never reach here, so they do not consume count.
func (c *calendario) allowed(t time.Time, emitted int) bool {
	if c.rep == nil {
		return true
	}
	if c.rep.Count != nil && emitted >= *c.rep.Count {
		return false
	}
	if c.rep.Until != "" && localDate(t, c.loc) > c.rep.Until {
		return false
	}
	return true
}

func localDate(t time.Time, loc *time.Location) string {
	return t.In(loc).Format("2006-01-02")
}

// sequence returns a generator over the unbounded firing list (limits are
// applied by eachFiring).
func (c *calendario) sequence() func() (time.Time, bool) {
	if c.rep == nil {
		first := c.firstHora()
		done := false
		return func() (time.Time, bool) {
			if done {
				return time.Time{}, false
			}
			done = true
			return first, true
		}
	}
	switch c.rep.Freq {
	case "DAILY":
		// Every firing is built from the first firing's local date plus
		// k*step days: never chained from the previous instant, so a firing
		// that falls in a DST gap cannot shift the later ones.
		y, m, d := c.firstHora().In(c.loc).Date()
		step := c.rep.intervalDays()
		k := 0
		return func() (time.Time, bool) {
			out := c.horaLocal(y, m, d+k*step)
			k++
			return out, true
		}
	case "HOURLY":
		first := c.firstHora()
		step := time.Duration(c.rep.intervalDays()) * time.Hour
		k := 0
		return func() (time.Time, bool) {
			out := first.Add(step * time.Duration(k))
			k++
			return out, true
		}
	case "WEEKLY":
		return c.weeklySequence()
	case "MONTHLY":
		return c.monthlySequence()
	}
	first, ok := c.firstFiring()
	return func() (time.Time, bool) {
		if !ok {
			return time.Time{}, false
		}
		ok = false
		return first, true
	}
}

// firstFiring returns the first firing: the first occurrence of `hora`
// strictly after the anchor that satisfies the day rule, if any.
func (c *calendario) firstFiring() (time.Time, bool) {
	if c.rep == nil {
		return c.firstHora(), true
	}
	switch c.rep.Freq {
	case "DAILY", "HOURLY":
		return c.firstHora(), true
	case "WEEKLY":
		return c.firstWeekly(), true
	case "MONTHLY":
		return c.firstMonthly()
	}
	return time.Time{}, false
}

// firstHora returns the first occurrence of `hora` strictly after the anchor.
func (c *calendario) firstHora() time.Time {
	y, m, d := c.anchor.In(c.loc).Date()
	t := c.horaLocal(y, m, d)
	if !t.After(c.anchor) {
		t = c.horaLocal(y, m, d+1)
	}
	return t
}

// horaLocal builds the wall time (y, m, d, c.hour, c.min) in c.loc applying
// the RFC 5545 3.3.5 rule: a nonexistent local time is interpreted with the
// UTC offset in effect before the gap (02:30 New York on a spring-forward day
// fires at 03:30), and an ambiguous one resolves to its first occurrence.
func (c *calendario) horaLocal(y int, m time.Month, d int) time.Time {
	u := time.Date(y, m, d, c.hour, c.min, 0, 0, time.UTC)
	preGap := offsetAt(time.Date(y, m, d-1, 23, 0, 0, 0, c.loc))
	offs := []int{preGap}
	for _, p := range []time.Time{
		time.Date(y, m, d, 0, 0, 0, 0, c.loc),
		time.Date(y, m, d, 12, 0, 0, 0, c.loc),
		time.Date(y, m, d+1, 0, 0, 0, 0, c.loc),
	} {
		if off := offsetAt(p); !containsInt(offs, off) {
			offs = append(offs, off)
		}
	}
	var occs []time.Time
	for _, off := range offs {
		cand := u.Add(-time.Duration(off) * time.Second)
		yy, mm, dd := cand.In(c.loc).Date()
		hh, mi, _ := cand.In(c.loc).Clock()
		if yy == y && mm == m && dd == d && hh == c.hour && mi == c.min {
			occs = append(occs, cand)
		}
	}
	if len(occs) > 1 {
		sort.Slice(occs, func(i, j int) bool { return occs[i].Before(occs[j]) })
		return occs[0]
	}
	if len(occs) == 1 {
		return occs[0]
	}
	// Nonexistent (a gap only comes from a spring-forward jump):
	// keep the offset in effect before the gap.
	return u.Add(-time.Duration(preGap) * time.Second)
}

func offsetAt(t time.Time) int {
	_, off := t.Zone()
	return off
}

func containsInt(xs []int, x int) bool {
	for _, v := range xs {
		if v == x {
			return true
		}
	}
	return false
}

// firstWeekly is the first occurrence of `hora` strictly after the anchor on a
// listed weekday. Without byday it is the plain first occurrence of `hora`;
// only that weekday repeats afterwards.
func (c *calendario) firstWeekly() time.Time {
	if len(c.rep.Byday) == 0 {
		return c.firstHora()
	}
	y, m, d := c.firstHora().In(c.loc).Date()
	set := make(map[time.Weekday]bool, len(c.rep.Byday))
	for _, token := range c.rep.Byday {
		_, wd, ok := parseBydayToken(token)
		if ok {
			set[wd] = true
		}
	}
	for i := 0; i < 8; i++ {
		t := c.horaLocal(y, m, d+i)
		if set[t.In(c.loc).Weekday()] {
			return t
		}
	}
	return c.horaLocal(y, m, d+8)
}

// weeklySequence yields the firings of every ISO week (WKST=MO) at a multiple
// of `interval` from the week of the first firing, on the listed weekdays.
func (c *calendario) weeklySequence() func() (time.Time, bool) {
	first := c.firstWeekly()
	offsets := c.weeklyOffsets(first)
	y, m, d := isoMonday(first.In(c.loc)).Date()
	week, day := 0, 0
	return func() (time.Time, bool) {
		for {
			if day >= len(offsets) {
				week++
				day = 0
				continue
			}
			shift := week*c.rep.intervalDays()*7 + offsets[day]
			day++
			// Built per firing from the ISO monday's local date: DST gaps
			// in one firing cannot shift the later ones.
			t := c.horaLocal(y, m, d+shift)
			if t.Before(first) {
				continue
			}
			return t, true
		}
	}
}

// weeklyOffsets returns the sorted ISO day offsets (Monday=0) of the week
// days that fire: the byday list, or the weekday of the first firing.
func (c *calendario) weeklyOffsets(first time.Time) []int {
	if len(c.rep.Byday) == 0 {
		return []int{isoOffset(first.Weekday())}
	}
	seen := map[int]bool{}
	offsets := []int{}
	for _, token := range c.rep.Byday {
		_, wd, ok := parseBydayToken(token)
		if !ok {
			continue
		}
		off := isoOffset(wd)
		if !seen[off] {
			seen[off] = true
			offsets = append(offsets, off)
		}
	}
	for i := 1; i < len(offsets); i++ {
		for j := i; j > 0 && offsets[j] < offsets[j-1]; j-- {
			offsets[j], offsets[j-1] = offsets[j-1], offsets[j]
		}
	}
	return offsets
}

// firstMonthly returns the first occurrence of `hora` strictly after the
// anchor whose day matches the MONTHLY rule (bymonthday or byday ordinal).
func (c *calendario) firstMonthly() (time.Time, bool) {
	y, m, _ := c.anchor.In(c.loc).Date()
	for i := 0; i < 1200; i++ {
		my, mm := addMonths(y, m, i)
		d, ok := monthlyDay(my, mm, c.rep)
		if !ok {
			continue
		}
		t := c.horaLocal(my, mm, d)
		if t.After(c.anchor) {
			return t, true
		}
	}
	return time.Time{}, false
}

// monthlySequence yields one firing per matching month, `interval` months
// apart from the month of the first firing. Months without the day are
// skipped entirely.
func (c *calendario) monthlySequence() func() (time.Time, bool) {
	first, ok := c.firstMonthly()
	if !ok {
		return func() (time.Time, bool) { return time.Time{}, false }
	}
	y, m, _ := first.In(c.loc).Date()
	step := c.rep.intervalDays()
	k := 0
	return func() (time.Time, bool) {
		for {
			my, mm := addMonths(y, m, k*step)
			k++
			d, ok := monthlyDay(my, mm, c.rep)
			if !ok {
				continue
			}
			return c.horaLocal(my, mm, d), true
		}
	}
}

// monthlyDay returns the day of month the rule fires on, if the month has it.
func monthlyDay(year int, month time.Month, rep *repeticion) (int, bool) {
	last := daysIn(year, month)
	if rep.Bymonthday != nil {
		n := *rep.Bymonthday
		if n == -1 {
			return last, true
		}
		if n >= 1 && n <= last {
			return n, true
		}
		return 0, false
	}
	if len(rep.Byday) == 0 {
		return 0, false
	}
	ordinal, wd, ok := parseBydayToken(rep.Byday[0])
	if !ok {
		return 0, false
	}
	if ordinal == -1 {
		return lastWeekday(year, month, wd), true
	}
	if ordinal < 1 {
		return 0, false
	}
	return nthWeekday(year, month, wd, ordinal)
}

func daysIn(year int, month time.Month) int {
	return time.Date(year, month+1, 0, 0, 0, 0, 0, time.UTC).Day()
}

// nthWeekday returns the day of the n-th (1..5) weekday of the month.
func nthWeekday(year int, month time.Month, wd time.Weekday, n int) (int, bool) {
	first := time.Date(year, month, 1, 0, 0, 0, 0, time.UTC)
	off := (int(wd) - int(first.Weekday()) + 7) % 7
	day := 1 + off + (n-1)*7
	if day > daysIn(year, month) {
		return 0, false
	}
	return day, true
}

// lastWeekday returns the day of the last weekday of the month.
func lastWeekday(year int, month time.Month, wd time.Weekday) int {
	last := daysIn(year, month)
	lastWD := time.Date(year, month, last, 0, 0, 0, 0, time.UTC).Weekday()
	off := (int(lastWD) - int(wd) + 7) % 7
	return last - off
}

func addMonths(year int, month time.Month, delta int) (int, time.Month) {
	total := year*12 + int(month) - 1 + delta
	return total / 12, time.Month(total%12 + 1)
}

func isoOffset(wd time.Weekday) int { return (int(wd) + 6) % 7 }

// isoMonday returns the local Monday midnight of the week that contains t.
func isoMonday(t time.Time) time.Time {
	y, m, d := t.Date()
	return time.Date(y, m, d, 0, 0, 0, 0, t.Location()).AddDate(0, 0, -isoOffset(t.Weekday()))
}

// parseBydayToken parses "MO" (ordinal 0), "1MO".."5SU" and "-1SU".
func parseBydayToken(token string) (int, time.Weekday, bool) {
	if len(token) < 2 {
		return 0, 0, false
	}
	wd, ok := weekdays[token[len(token)-2:]]
	if !ok {
		return 0, 0, false
	}
	switch prefix := token[:len(token)-2]; prefix {
	case "":
		return 0, wd, true
	case "-1":
		return -1, wd, true
	case "1", "2", "3", "4", "5":
		ord, err := strconv.Atoi(prefix)
		if err != nil {
			return 0, 0, false
		}
		return ord, wd, true
	}
	return 0, 0, false
}

var weekdays = map[string]time.Weekday{
	"MO": time.Monday,
	"TU": time.Tuesday,
	"WE": time.Wednesday,
	"TH": time.Thursday,
	"FR": time.Friday,
	"SA": time.Saturday,
	"SU": time.Sunday,
}
