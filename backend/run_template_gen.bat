@echo off
cd /d C:\Users\vamsh\OneDrive\Documents\Desktop\SSGA_NEW\backend
./mvnw.cmd exec:java -Dexec.mainClass="SSGA_Backend.importer.ExcelTemplateGenerator" -Dexec.classpathScope=compile
pause