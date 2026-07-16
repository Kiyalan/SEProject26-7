# 在中文路径下绕过 spring-boot:run 的 classpath 乱码问题
Set-Location $PSScriptRoot
mvn -q compile dependency:build-classpath "-Dmdep.outputFile=cp.txt"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$cp = "target/classes;" + (Get-Content cp.txt -Raw).Trim()
& java "-Dfile.encoding=UTF-8" -cp $cp com.repopilot.RepoPilotApplication @args
