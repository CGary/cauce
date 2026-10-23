// Package migrations embeds the SQL files applied by the store at startup.
package migrations

import "embed"

// FS holds migrations/*.sql. Files are applied in lexical order.
//
//go:embed *.sql
var FS embed.FS
