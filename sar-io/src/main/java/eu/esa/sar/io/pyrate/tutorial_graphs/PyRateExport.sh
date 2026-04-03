#!/bin/bash 

################################################################
# PyRateExport.sh
#   A script that will prepare Sentinel-1 SAR products for 
#   SBAS processing with PyRate.
# 
################################################################


################################################################
# Configuration section 
#
# This top section allows for easy access to config variables
# to specify processing locations. 
#
# intermediate_deletion
#   Set to "y" if you wish to clear out intermediate data after 
#   processing is complete.
#
# intermediate_processing_location
#   Set to a folder on a drive that will have adequate disk space.
#   This folder will be where the individual graphs will write out
#   data for each step.
#
# source_product_location
#   Set this to be the folder containing all your Sentinel-1 zipped
#   products that you wish to perform PyRate SBAS processing on. 
#
# sentinel1_swath
#   Choose the swath that contains the area you wish to perform
#   analysis on.
#
# sentinel1_burst_min and sentinel1_burst_max
#   Choose the burst range you wish to select for subsetting your
#   data. 
# 
# snaphu_export_folder
#   SNAPHU requires short path names to function properly. 
#   Set this to an empty folder close to the root of a drive 
#   (e.g. /tmp/snaphu, /dataproc/snaphu, /snapproc/snaphu)
# 
# snaphu_install_location
#   The Batch SNAPHU Unwrap tool introduced with the new PyRate
#   workflow will download a SNAPHU binary provided by ESA, which is
#   compatible with 32bit and 64 bit Windows machines, and x86_64 
#   UNIX systems (MacOS and Linux). UNIX systems running on a different
#   architecture, such as the M1 and M2 chips on Apple devices, or
#   Linux machines running on PowerPC or 32-bit processor architectures, 
#   will need to provide their own SNAPHU binaries. The snaphu_install_location
#   binary can be set to a direct link to a valid SNAPHU binary in these
#   cases to skip the download step. 
#
# subset_xmin, subset_xmax, subset_ymin, subset_ymax 
#   Pixel coordinates for subsetting input data. 
#   SNAPHU does not work well on larger areas so subsetting is
#   necessary for a fast, efficient, and stable unwrap. 
#   Be sure to consult your source data to ensure the subset is being 
#   run to capture your area of interest. 
#################################################################

intermediate_deletion="y"

intermediate_processing_location="./intermediateProcessing"
source_product_location="./input"

output_pyrate_folder="./pyrateInput"

sentinel1_swath="IW3"
sentinel1_burst_min=4
sentinel1_burst_max=4

subset_xmin=14000
subset_ymin=0
subset_xmax=21000
subset_ymax=1000

snaphu_install_location="/tmp"
snaphu_export_folder="/tmp/snpProc"

# Add SNAP bin folder to PATH if it is not already. 
export PATH=$PATH:/usr/local/snap/bin

###################################################
#
#  End of variable configuration section. 
#  SNAP graph processing steps below.
#
###################################################

# Method to clear out a specified intermediate folder.
delete_folder () {
    if [ $intermediate_deletion == "y" ];
    then
        rm -rf $1
    fi 
}

# Create intermediate folders
for ((i=0; i <= 5; i++)); do
    mkdir $intermediate_processing_location/$i
done 

# Create PyRate folder
mkdir $output_pyrate_folder

# Apply orbital file correction and split into subswath & burst range. 
for a in input/*.zip; do \
    aOut=$(echo "$a" | sed 's/input/intermediateProcessing\/0/g')
    gpt "00_Orb_Split.xml" -PsourceProduct="$a" -Pswath="$sentinel1_swath" -PburstMin="$sentinel1_burst_min" -PburstMax="$sentinel1_burst_max" -PoutputFile="$aOut" ; done 

# Create a file listing for all the files created in the previous step
file_list=""

# Loop through the files in the directory ending with .dim
for file in intermediateProcessing/0/*.dim; do
    # Check if the file exists and is a regular file
    if [ -f "$file" ]; then
        # Concatenate the file name to the list, delimited by semi-colon
        file_list+=",$file"
    fi
done

# Remove the leading semi-colon (if any) from the file list
file_list=${file_list#","}

# Perform stack creation 
gpt "01_Create_Stack.xml" -PinputFileList="$file_list" -PoutputFile="$intermediate_processing_location/1/imgStack.dim"

delete_folder $intermediate_processing_location/0

# Perform SNAPHU export preparation and interferogram generation 
gpt "02_00_Create_Interferograms.xml" -PinputProduct="$intermediate_processing_location/1/imgStack.dim"  -PoutputProduct="$intermediate_processing_location/2/mmifg.dim" -Pxmin=$subset_xmin -Pymin=$subset_ymin -Pxmax=$subset_xmax -Pymax=$subset_ymax

# Clear out image stack 
delete_folder intermediateProcessing/1

# Produce unwrap input for SNAPHU unwrapping
gpt "02_01_UnwrapFileGen.xml" -PsnaphuFolder="$snaphu_export_folder" -PinputProduct="$intermediate_processing_location/2/mmifg.dim"

# Write out SNAPHU unwrap to folder. 
gpt "03_BatchUnwrap.xml" -PsourceProduct="$intermediate_processing_location/2/mmifg.dim" -PsnaphuProcessingFolder="$snaphu_export_folder/mmifg" -Psnaphu_install_location="$snaphu_install_location" -PoutputProduct="$intermediate_processing_location/03/unwrapped.dim"
3
delete_folder intermediateProcessing/2

# Apply terrain correction to our unwrapped stack of interferograms 
gpt "04_TerrainCorrection.xml" -PsourceProduct="$intermediate_processing_location/03/unwrapped.dim" -PoutputProduct="$intermediate_processing_location/04/TC.dim"

delete_folder intermediateProcessing/3

# Write stack to PyRate format
gpt "05_PyRateWriting.xml" -PsourceProduct="$intermediate_processing_location/04/TC.dim" -PoutputProduct="$output_pyrate_folder/pyrate.conf"

delete_folder intermediateProcessing/4


#########################################################################
#
# End of SNAP pre-processing steps. PyRate processing from hereon out.
#
#########################################################################

cd $output_pyrate_folder

pyrate prepifg -f input_parameters.conf 
pyrate correct -f input_parameters.conf 
pyrate timeseries -f input_parameters.conf 
pyrate merge -f input_parameters.conf 
