/**
 * Nombres de zona de Windows → IANA (CLDR `windowsZones`).
 *
 * Outlook y Exchange escriben `TZID=Romance Standard Time` en sus `.ics`. Sin
 * traducirlos, `Intl` lanza `RangeError` y aborta toda la importación, así que
 * la tabla vive acá: es el único puente entre el vocabulario de Windows y el
 * de IANA que usa el resto del código.
 *
 * Solo América y Europa: es lo que aparece en el tratamiento del usuario.
 */
const WINDOWS_ZONES: Record<string, string> = {
  // América del Norte
  'Alaska Standard Time': 'America/Anchorage',
  'Atlantic Standard Time': 'America/Halifax',
  'Canada Central Standard Time': 'America/Regina',
  'Central Standard Time': 'America/Chicago',
  'Central Standard Time (Mexico)': 'America/Mexico_City',
  'Eastern Standard Time': 'America/New_York',
  'Eastern Standard Time (Mexico)': 'America/Cancun',
  'Hawaii Standard Time': 'Pacific/Honolulu',
  'Mexico Standard Time': 'America/Mexico_City',
  'Mexico Standard Time 2': 'America/Chihuahua',
  'Mountain Standard Time': 'America/Denver',
  'Mountain Standard Time (Mexico)': 'America/Chihuahua',
  'Newfoundland Standard Time': 'America/St_Johns',
  'Pacific Standard Time': 'America/Los_Angeles',
  'Pacific Standard Time (Mexico)': 'America/Tijuana',
  'US Eastern Standard Time': 'America/Indiana/Indianapolis',
  'US Mountain Standard Time': 'America/Phoenix',
  'Yukon Standard Time': 'America/Whitehorse',
  // América Central y Caribe
  'Central America Standard Time': 'America/Guatemala',
  'Cuba Standard Time': 'America/Havana',
  'Turks And Caicos Standard Time': 'America/Grand_Turk',
  // América del Sur
  'Argentina Standard Time': 'America/Argentina/Buenos_Aires',
  'Bolivia Standard Time': 'America/La_Paz',
  'Brazil Eastern Standard Time': 'America/Sao_Paulo',
  'Central Brazilian Standard Time': 'America/Cuiaba',
  'E. South America Standard Time': 'America/Sao_Paulo',
  'Pacific SA Standard Time': 'America/Santiago',
  'SA Eastern Standard Time': 'America/Cayenne',
  'SA Pacific Standard Time': 'America/Bogota',
  'SA Western Standard Time': 'America/La_Paz',
  'Venezuela Standard Time': 'America/Caracas',
  // Europa
  'Azores Standard Time': 'Atlantic/Azores',
  'Central Europe Standard Time': 'Europe/Budapest',
  'Central European Standard Time': 'Europe/Warsaw',
  'E. Europe Standard Time': 'Europe/Chisinau',
  'FLE Standard Time': 'Europe/Kiev',
  'GTB Standard Time': 'Europe/Bucharest',
  'Greenwich Standard Time': 'Atlantic/Reykjavik',
  'GMT Standard Time': 'Europe/London',
  'Romance Standard Time': 'Europe/Paris',
  'Russian Standard Time': 'Europe/Moscow',
  'Turkey Standard Time': 'Europe/Istanbul',
  'W. Europe Standard Time': 'Europe/Berlin',
  // Sin zona local
  UTC: 'UTC',
}

const BY_LOWER = new Map(
  Object.entries(WINDOWS_ZONES).map(([name, zone]) => [name.toLowerCase(), zone]),
)

/**
 * IANA válida, o la IANA a la que traduce un nombre de Windows, o `null` si la
 * zona no se conoce (el evento se rechaza, no el archivo entero).
 */
export function resolveTimeZone(tzid: string): string | null {
  const name = tzid.trim().replace(/^\/mozilla\.org\/20050126_1\//i, '')
  const candidate = BY_LOWER.get(name.toLowerCase()) ?? name
  return isValidTimeZone(candidate) ? candidate : null
}

function isValidTimeZone(tz: string): boolean {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: tz })
    return true
  } catch {
    return false
  }
}
