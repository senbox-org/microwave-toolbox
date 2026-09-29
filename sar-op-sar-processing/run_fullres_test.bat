@echo off
REM Run the full-resolution GSLC vs Traditional comparison on the user's ASAR pair.
REM This is slow (~30-60 min) and memory-hungry. Allocates 20 GB to the test JVM.
REM
REM Run from the microwave-toolbox root directory.

cd /d %~dp0..

mvn -pl sar-op-sar-processing ^
    -Dtest=GSLCVsTraditionalComparisonTest#testFullResolution ^
    -Dsurefire.jvm.args="-enableassertions -Xmx20g -Dfile.encoding=UTF-8 --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.awt.image=ALL-UNNAMED --add-opens java.desktop/sun.awt.shell=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.desktop/javax.swing=ALL-UNNAMED --add-opens java.base/java.security=ALL-UNNAMED --add-exports java.desktop/com.sun.imageio.plugins.jpeg=ALL-UNNAMED" ^
    test
