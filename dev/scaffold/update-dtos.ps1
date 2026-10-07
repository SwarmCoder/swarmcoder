$path = "C:\work\swarmcoder\sc-console-api\src\main\java\com\swarmcoder\console\api\*.java"

Get-ChildItem -Path $path | ForEach-Object {
    $content = Get-Content $_.FullName -Raw
    
    # Signatures
    $content = $content -replace 'public void writeToBuffer\(ByteBuffer buffer\)', 'public void writeToBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper)'
    $content = $content -replace 'public void readFromBuffer\(ByteBuffer buffer\)', 'public void readFromBuffer(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper)'
    
    # writeValue
    $content = $content -replace 'BinarySerializer\.writeValue\(buffer,\s*([^)]+)\)', 'BinarySerializer.writeValue(buffer, $1, mapper)'
    
    # readValue inside helper methods
    $content = $content -replace 'BinarySerializer\.readValue\(buffer\)', 'BinarySerializer.readValue(buffer, mapper)'
    
    # Custom read methods signatures in SessionSummaryDto
    $content = $content -replace 'static int readInt\(ByteBuffer buffer\)', 'static int readInt(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper)'
    $content = $content -replace 'static long readLong\(ByteBuffer buffer\)', 'static long readLong(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper)'
    
    # Calls to custom read methods
    $content = $content -replace 'readInt\(buffer\)', 'readInt(buffer, mapper)'
    $content = $content -replace 'readLong\(buffer\)', 'readLong(buffer, mapper)'

    # Just in case readBoolean is there
    $content = $content -replace 'static boolean readBoolean\(ByteBuffer buffer\)', 'static boolean readBoolean(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper)'
    $content = $content -replace 'readBoolean\(buffer\)', 'readBoolean(buffer, mapper)'
    
    # Just in case readObject is there
    $content = $content -replace 'static Object readObject\(ByteBuffer buffer\)', 'static Object readObject(ByteBuffer buffer, com.zeroz4j.api.ObjectMapper mapper)'
    $content = $content -replace 'readObject\(buffer\)', 'readObject(buffer, mapper)'
    
    # InsightsDto list parsing inside InsightsDto
    # Usually it's something like readList(buffer), let's just blanket anything (buffer) that is our own read helper, but that's risky. 
    # Let's hope there are no other custom helpers.

    Set-Content -Path $_.FullName -Value $content
}
