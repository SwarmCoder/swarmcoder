$path = "C:\work\swarmcoder\sc-console-api\src\main\java\com\swarmcoder\console\api\*.java"

Get-ChildItem -Path $path | ForEach-Object {
    $content = Get-Content $_.FullName -Raw
    
    $content = $content -replace '\.size\(\, mapper\)', '.size(), mapper'
    $content = $content -replace 'writeToBuffer\(buffer\)', 'writeToBuffer(buffer, mapper)'
    $content = $content -replace 'readFromBuffer\(buffer\)', 'readFromBuffer(buffer, mapper)'
    
    # Also I should check if there's any other place where the regex missed the parenthesis, e.g. .length() ?
    # Let's just fix .size(, mapper)
    
    Set-Content -Path $_.FullName -Value $content
}
