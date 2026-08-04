@echo off
setlocal

call "D:\Microsoft Visual Studio\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b %errorlevel%

set ROOT=%~dp0..\..\..
set SDK=%ROOT%\build\native-webview2-sdk
set OUTPUT=%ROOT%\build\native\windows-x86_64

if not exist "%SDK%\include\WebView2.h" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepare-sdk.ps1" "%SDK%"
  if errorlevel 1 exit /b %errorlevel%
)

if not exist "%OUTPUT%" mkdir "%OUTPUT%"

cl /nologo /std:c++17 /EHsc /O2 /MT /LD /utf-8 ^
  "%~dp0douyin_webview2.cpp" ^
  /I"D:\jdk21\include" ^
  /I"D:\jdk21\include\win32" ^
  /I"%SDK%\include" ^
  /link /OUT:"%OUTPUT%\douyinwebview.dll" ^
  "%SDK%\x64\WebView2LoaderStatic.lib" version.lib user32.lib ole32.lib advapi32.lib

exit /b %errorlevel%
