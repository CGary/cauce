// Command cauce-cli is the command line client for agents (D21,
// docs/PLAN-CLI.md): it reads projections and writes events through the same
// HTTP API as the web console, with device_id "cli".
package main

import (
	"os"

	"cauce/server/internal/cli"
)

func main() {
	os.Exit(cli.Run(os.Args[1:], cli.DefaultEnv()))
}
