$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
$env:ANDROID_HOME = "C:\Users\gilma\AppData\Local\Android\Sdk"

cd c:\GDW
# Garantir que o gradlew seja executável (em Windows é .bat)
.\gradlew.bat assembleRelease
