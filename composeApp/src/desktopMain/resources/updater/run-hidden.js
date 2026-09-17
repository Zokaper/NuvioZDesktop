// Starts the MSI update helper without a console window.
//
// Java cannot pass CREATE_NO_WINDOW, so a PowerShell started straight from the app
// flashes a console - or, where Windows Terminal is the default terminal, opens a
// Terminal window that -WindowStyle Hidden does not reliably hide. wscript is a GUI
// host with no console, and Run with window style 0 starts PowerShell hidden.
// The environment, which carries the helper's inputs, is inherited unchanged.
var shell = WScript.CreateObject("WScript.Shell");
var script = WScript.Arguments(0);
shell.Run('powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + script + '"', 0, false);
