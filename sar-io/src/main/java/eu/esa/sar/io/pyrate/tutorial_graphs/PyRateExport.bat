@echo off
REM PyRateExport.bat
REM A script that will prepare Sentinel-1 SAR products for SBAS processing with PyRate.

REM Configuration section
REM This top section allows for easy access to config variables to specify processing locations.

REM Set to "y" if you wish to clear out intermediate data after processing is complete.
set intermediate_deletion=y

REM Set to a folder on a drive that will have adequate disk space. This folder will be where the individual graphs will write out data for each step.
set intermediate_processing_location=./intermediateProcessing

REM Set this to be the folder containing all your Sentinel-1 zipped products that you wish to perform PyRate SBAS processing on.
set source_product_location=./input

REM Choose the swath that contains the area you wish to perform analysis on.
set sentinel1_swath=IW3

REM Choose the burst range you wish to select for subsetting your data.
set sentinel1_burst_min=4
set sentinel1_burst_max=4

REM Pixel coordinates for subsetting input data.
REM SNAPHU does not work well on larger areas, so subsetting is necessary for a fast, efficient, and stable unwrap.
REM Be sure to consult your source data to ensure the subset is being run to capture your area of interest.
set subset_xmin=14000
set subset_ymin=0
set subset_xmax=21000
set subset_ymax=1000

REM SNAPHU requires short path names to function properly. Set this to an empty folder close to the root of a drive (e.g. C:\tmp\snaphu, D:\dataproc\snaphu, E:\snapproc\snaphu)
set snaphu_export_folder=C:\tmp\snpProc

REM The Batch SNAPHU Unwrap tool introduced with the new PyRate workflow will download a SNAPHU binary provided by ESA, which is compatible with 32-bit and 64-bit Windows machines, and x86_64 UNIX systems (MacOS and Linux). UNIX systems running on a different architecture, such as the M1 and M2 chips on Apple devices, or Linux machines running on PowerPC or 32-bit processor architectures, will need to provide their own SNAPHU binaries. The snaphu_install_location binary can be set to a direct link to a valid SNAPHU binary in these cases to skip the download step.
set snaphu_install_location=C:\tmp


REM Add SNAP bin folder to PATH if it is not already.
set "PATH=%PATH%;C:\Program Files\snap\bin"

REM End of variable configuration section. SNAP graph processing steps below.

REM Method to clear out a specified intermediate folder.
:delete_folder
if /I "%intermediate_deletion%"=="y" (
    rmdir /s /q "%~1"
)

REM Create intermediate folders
for /L %%i in (0, 1, 5) do (
    mkdir "%intermediate_processing_location%\%%i"
)

REM Create PyRate folder
mkdir "%output_pyrate_folder%"

REM Apply orbital file correction and split into subswath & burst range.
for %%a in (%source_product_location%\*.zip) do (
    set "aOut=%intermediate_processing_location%\0\%%~Na"
    gpt "00_Orb_Split.xml" -PsourceProduct="%%a" -Pswath="%sentinel1_swath%" -PburstMin=%sentinel1_burst_min% -PburstMax=%sentinel1_burst_max% -PoutputFile="!aOut!"
)

REM Create a file listing for all the files created in the previous step
set "file_list="

REM Loop through the files in the directory ending with .dim
for %%f in (%intermediate_processing_location%\0\*.dim) do (
    REM Check if the file exists and is a regular file
    if exist "%%f" (
        REM Concatenate the file name to the list, delimited by semi-colon
        set "file_list=!file_list!,%%f"
    )
)

REM Remove the leading semi-colon (if any) from the file list
set "file_list=%file_list:~1%"

REM Perform stack creation
gpt "01_Create_Stack.xml" -PinputFileList="%file_list%" -PoutputFile="%intermediate_processing_location%\1\imgStack.dim"

call :delete_folder "%intermediate_processing_location%\0"

REM Perform SNAPHU export preparation and interferogram generation
gpt "02_00_Create_Interferograms.xml" -PinputProduct="%intermediate_processing_location%\1\imgStack.dim" -PoutputProduct="%intermediate_processing_location%\2\mmifg.dim" -Pxmin=%subset_xmin% -Pymin=%subset_ymin% -Pxmax=%subset_xmax% -Pymax=%subset_ymax%

call :delete_folder "%intermediate_processing_location%\1"

REM Produce unwrap input for SNAPHU unwrapping
gpt "02_01_UnwrapFileGen.xml" -PsnaphuFolder="%snaphu_export_folder%" -PinputProduct="%intermediate_processing_location%\2\mmifg.dim"

REM Write out SNAPHU unwrap to folder.
gpt "03_BatchUnwrap.xml" -PsourceProduct="%intermediate_processing_location%\2\mmifg.dim" -PsnaphuProcessingFolder="%snaphu_export_folder%\mmifg" -Psnaphu_install_location="%snaphu_install_location%" -PoutputProduct="%intermediate_processing_location%\03\unwrapped.dim"

call :delete_folder "%intermediate_processing_location%\2"

REM Apply terrain correction to our unwrapped stack of interferograms
gpt "04_TerrainCorrection.xml" -PsourceProduct="%intermediate_processing_location%\03\unwrapped.dim" -PoutputProduct="%intermediate_processing_location%\04\TC.dim"

call :delete_folder "%intermediate_processing_location%\03"

REM Write stack to PyRate format
gpt "05_PyRateWriting.xml" -PsourceProduct="%intermediate_processing_location%\04\TC.dim" -PoutputProduct="%output_pyrate_folder%\pyrate.conf"

call :delete_folder "%intermediate_processing_location%\04"

REM End of SNAP pre-processing steps. PyRate processing from hereon out.

cd /d "%output_pyrate_folder%"

pyrate prepifg -f input_parameters.conf
pyrate correct -f input_parameters.conf
pyrate timeseries -f input_parameters.conf
pyrate merge -f input_parameters.conf

exit /b

REM Function to clear out a specified intermediate folder.
:delete_folder
if /I "%intermediate_deletion%"=="y" (
    rmdir /s /q "%~1"
)
exit /b
