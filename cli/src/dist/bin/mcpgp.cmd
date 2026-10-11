@echo off
rem mcpgp: runs the CLI jar of this installation (lib\mcpgp-cli.jar) with Java 21 or newer,
rem from JAVA_HOME when it is set, else the java on PATH. MCPGP_JAVA_OPTS adds JVM options.
setlocal
set "MCPGP_HOME=%~dp0.."
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java.exe") else (set "JAVA=java")
"%JAVA%" %MCPGP_JAVA_OPTS% -jar "%MCPGP_HOME%\lib\mcpgp-cli.jar" %*
exit /b %ERRORLEVEL%
