# 别墅体素模型生成器：产出 markov-output.json（x-fastest），并同步翻译映射。
# 符号表: F=1 地基, W=2 墙, D=3 门, U=4 窗, R=5 屋顶 (0 = 空)
$ErrorActionPreference = 'Stop'

$symbols = '0FWDUR'  # 占位0(空); F=1,W=2,D=3,U=4,R=5，与 cells 取值对齐

$empty = '000000000000'
$hollow = 'W0000000000W'

# 6 层, 每层 12 行(y0..y11), 每行 12 字符(x0..x11)
$layers = @(
  # z=0 地基
  @('FFFFFFFFFFFF','FFFFFFFFFFFF','FFFFFFFFFFFF','FFFFFFFFFFFF','FFFFFFFFFFFF','FFFFFFFFFFFF',
    'FFFFFFFFFFFF','FFFFFFFFFFFF','FFFFFFFFFFFF','FFFFFFFFFFFF','FFFFFFFFFFFF','FFFFFFFFFFFF'),
  # z=1 一层: 正面(y0)双开门
  @('WWWWDDWWWWWW',$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,'WWWWWWWWWWWW'),
  # z=2 二层底: 正面开窗
  @('WWUWWWWWWUWW',$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,'WWWWWWWWWWWW'),
  # z=3 二层: 前后开窗
  @('WWUWWWWWWUWW',$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,$hollow,'WWUWWWWWWUWW'),
  # z=4 屋顶底(全红)
  @('RRRRRRRRRRRR','RRRRRRRRRRRR','RRRRRRRRRRRR','RRRRRRRRRRRR','RRRRRRRRRRRR','RRRRRRRRRRRR',
    'RRRRRRRRRRRR','RRRRRRRRRRRR','RRRRRRRRRRRR','RRRRRRRRRRRR','RRRRRRRRRRRR','RRRRRRRRRRRR'),
  # z=5 屋顶脊(收拢到中央 8x8)
  @($empty,$empty,'00RRRRRRRR00','00RRRRRRRR00','00RRRRRRRR00','00RRRRRRRR00',
    '00RRRRRRRR00','00RRRRRRRR00','00RRRRRRRR00','00RRRRRRRR00',$empty,$empty)
)

$index = @{ 'F'=1; 'W'=2; 'D'=3; 'U'=4; 'R'=5 }

function Get-Cells($layers, $index) {
  $cells = [System.Collections.Generic.List[int]]::new()
  foreach ($layer in $layers) {
    foreach ($row in $layer) {
      foreach ($ch in $row.ToCharArray()) {
        $v = 0
        if ($index.ContainsKey([string]$ch)) { $v = $index[[string]$ch] }
        $cells.Add($v)
      }
    }
  }
  return ,$cells
}

$cells = Get-Cells $layers $index
if ($cells.Count -ne (12*12*6)) { throw "细胞计数错误: $($cells.Count)" }

$builder = [System.Text.StringBuilder]::new()
[void]$builder.Append('{"size":[12,12,6],"symbols":"0FWDUR","seed":1,"order":"x-fastest","cells":[')
$first = $true
foreach ($c in $cells) {
  if (-not $first) { [void]$builder.Append(',') }
  $first = $false
  [void]$builder.Append($c)
}
[void]$builder.Append(']}')
$json = $builder.ToString()

$targets = @( (Join-Path (Get-Location) 'run\markov-output.json'),
               (Join-Path $env:APPDATA '.minecraft\markov-output.json') )
foreach ($t in $targets) {
  $dir = Split-Path -Parent $t
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  Set-Content -Path $t -Value $json -Encoding UTF8
  Write-Host "JSON -> $t ($((Get-Item $t).Length) bytes)"
}

# 追加门窗翻译映射（幂等）
$added = @('U=minecraft:glass','D=minecraft:dark_oak_planks')
foreach ($t in @( (Join-Path (Get-Location) 'run\translation_marlov.txt'),
                   (Join-Path $env:APPDATA '.minecraft\translation_marlov.txt') )) {
  $hasAdd = if (Test-Path $t) { Get-Content $t -Raw } else { '' }
  $lines = if (Test-Path $t) { Get-Content $t } else { @() }
  foreach ($line in $added) {
    if ($hasAdd -notmatch [regex]::Escape($line)) {
      $lines += $line
    }
  }
  if (Test-Path $t) {
    $keep = $lines | Where-Object { $_ -notmatch '^U=.*' }
    # 已有 U/D 行则替换，避免重复
    $keep = $keep | Where-Object { $_ -notmatch '^U=' }
    $keep = $keep | Where-Object { $_ -notmatch '^D=' }
    $keep = @($keep) + @($added)
    Set-Content -Path $t -Value $keep -Encoding UTF8
  } else {
    $head = @('# MarkovJuniorMC 字符 -> 方块ID 翻译规则', 'W=minecraft:spruce_log', 'F=minecraft:stone_bricks', 'R=minecraft:red_concrete')
    Set-Content -Path $t -Value (@($head) + @($added)) -Encoding UTF8
  }
  Write-Host "translation -> $t"
}
Write-Host "done: $($cells.Count) cells"