param([int]$ProcId, [string]$Out, [double]$Scale = 0)
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices; using System.Text;
public class W {
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc f, IntPtr l);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint f);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
}
"@
[void][W]::SetProcessDPIAware()
$found = $null; $best = 0
[W]::EnumWindows({ param($h, $l)
  $p = 0; [void][W]::GetWindowThreadProcessId($h, [ref]$p)
  if ($p -eq $ProcId -and [W]::IsWindowVisible($h)) {
    $r = New-Object W+RECT; [void][W]::GetWindowRect($h, [ref]$r)
    $a = ($r.R - $r.L) * ($r.B - $r.T)
    if ($a -gt $script:best) { $script:best = $a; $script:found = $h }
  }
  return $true }, [IntPtr]::Zero) | Out-Null
if (-not $found) { "no window"; exit 1 }
$r = New-Object W+RECT; [void][W]::GetWindowRect($found, [ref]$r)
$w = $r.R - $r.L; $hgt = $r.B - $r.T
$bmp = New-Object System.Drawing.Bitmap $w, $hgt
$g = [System.Drawing.Graphics]::FromImage($bmp); $hdc = $g.GetHdc()
[void][W]::PrintWindow($found, $hdc, 2); $g.ReleaseHdc($hdc); $g.Dispose()
if ($Scale) { $sm = New-Object System.Drawing.Bitmap ([int]($w/$Scale)), ([int]($hgt/$Scale)); $g2=[System.Drawing.Graphics]::FromImage($sm); $g2.DrawImage($bmp,0,0,$sm.Width,$sm.Height); $g2.Dispose(); $bmp=$sm }
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
"saved $Out ${w}x${hgt} rect=$($r.L),$($r.T)"
