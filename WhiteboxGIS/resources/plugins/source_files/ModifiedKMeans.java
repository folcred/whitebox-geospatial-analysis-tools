/*
 * Copyright (C) 2011-2012 Dr. John Lindsay <jlindsay@uoguelph.ca>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package plugins;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Random;
import whitebox.geospatialfiles.WhiteboxRaster;
import whitebox.geospatialfiles.WhiteboxRasterBase.DataScale;
import whitebox.geospatialfiles.WhiteboxRasterInfo;
import whitebox.interfaces.WhiteboxPlugin;
import whitebox.interfaces.WhiteboxPluginHost;

/**
 * This tool is an unsupervised classification method to be applied to multi-spectral remotely sensed imagery.
 * @author Dr. John Lindsay email: jlindsay@uoguelph.ca
 */
public class ModifiedKMeans implements WhiteboxPlugin {

    private WhiteboxPluginHost myHost = null;
    private String[] args;
    /**
     * Used to retrieve the plugin tool's name. This is a short, unique name containing no spaces.
     * @return String containing plugin name.
     */
    @Override
    public String getName() {
        return "ModifiedKMeans";
    }
    /**
     * Used to retrieve the plugin tool's descriptive name. This can be a longer name (containing spaces) and is used in the interface to list the tool.
     * @return String containing the plugin descriptive name.
     */
    @Override
    public String getDescriptiveName() {
        return "Modified k-Means Classification";
    }
    /**
     * Used to retrieve a short description of what the plugin tool does.
     * @return String containing the plugin's description.
     */
    @Override
    public String getToolDescription() {
        return "Performs a modified k-means classification on a multi-spectral dataset.";
    }
    /**
     * Used to identify which toolboxes this plugin tool should be listed in.
     * @return Array of Strings.
     */
    @Override
    public String[] getToolbox() {
        String[] ret = {"ImageClass"};
        return ret;
    }
    /**
     * Sets the WhiteboxPluginHost to which the plugin tool is tied. This is the class
     * that the plugin will send all feedback messages, progress updates, and return objects.
     * @param host The WhiteboxPluginHost that called the plugin tool.
     */  
    @Override
    public void setPluginHost(WhiteboxPluginHost host) {
        myHost = host;
    }
    /**
     * Used to communicate feedback pop-up messages between a plugin tool and the main Whitebox user-interface.
     * @param feedback String containing the text to display.
     */
    private void showFeedback(String message) {
        if (myHost != null) {
            myHost.showFeedback(message);
        } else {
            System.out.println(message);
        }
    }
    /**
     * Used to communicate a return object from a plugin tool to the main Whitebox user-interface.
     * @return Object, such as an output WhiteboxRaster.
     */
    private void returnData(Object ret) {
        if (myHost != null) {
            myHost.returnData(ret);
        }
    }
    private int previousProgress = 0;
    private String previousProgressLabel = "";
    /**
     * Used to communicate a progress update between a plugin tool and the main Whitebox user interface.
     * @param progressLabel A String to use for the progress label.
     * @param progress Float containing the progress value (between 0 and 100).
     */
    private void updateProgress(String progressLabel, int progress) {
        if (myHost != null && ((progress != previousProgress)
                || (!progressLabel.equals(previousProgressLabel)))) {
            myHost.updateProgress(progressLabel, progress);
        }
        previousProgress = progress;
        previousProgressLabel = progressLabel;
    }
    /**
     * Used to communicate a progress update between a plugin tool and the main Whitebox user interface.
     * @param progress Float containing the progress value (between 0 and 100).
     */
    private void updateProgress(int progress) {
        if (myHost != null && progress != previousProgress) {
            myHost.updateProgress(progress);
        }
        previousProgress = progress;
    }
    /**
     * Sets the arguments (parameters) used by the plugin.
     * @param args An array of string arguments.
     */
    @Override
    public void setArgs(String[] args) {
        this.args = args.clone();
    }
    private boolean cancelOp = false;
    /**
     * Used to communicate a cancel operation from the Whitebox GUI.
     * @param cancel Set to true if the plugin should be canceled.
     */
    @Override
    public void setCancelOp(boolean cancel) {
        cancelOp = cancel;
    }

    private void cancelOperation() {
        showFeedback("Operation cancelled.");
        updateProgress("Progress: ", 0);
    }
    private boolean amIActive = false;
    /**
     * Used by the Whitebox GUI to tell if this plugin is still running.
     * @return a boolean describing whether or not the plugin is actively being used.
     */
    @Override
    public boolean isActive() {
        return amIActive;
    }

    /**
     * Used to execute this plugin tool.
     */
    @Override
    public void run() {
        amIActive = true;

        String inputFilesString = null;
        String[] imageFiles = null;
        String outputHeader = null;
        WhiteboxRasterInfo[] images = null;
        WhiteboxRaster ouptut = null;
        int nCols = 0;
        int nRows = 0;
        double z;
        int numClasses = 0;
        int numImages;
        int progress = 0;
        int col, row;
        int a, i, j;
        double[][] data;
        double noData = -32768;
        double[][] classCentres = null;
        double[] classCentre;
        ArrayList<double[]> centres = new ArrayList<double[]>();
        double[][] imageMetaData;
        long[] numPixelsInEachClass;
        int maxIterations = 100;
        double dist, minDist;
        int whichClass;
        //double minAdjustment = 10;
        byte initializationMode = 0; // maximum dispersion along diagonal
        long numPixelsChanged = 0;
        long totalNumCells = 0;
        boolean totalNumCellsCounted = false;
        double percentChanged = 0;
        double percentChangedThreshold = 1.0;
        double centroidMergeDist = 30;
        int minimumAllowableClassSize = 1;
        int initialNumClasses = 10000;
        double maxDist = Double.POSITIVE_INFINITY;
        int unassignedClass = -1;
        boolean isNoDataPixel;

        if (args.length <= 0) {
            showFeedback("Plugin parameters have not been set.");
            return;
        }

        // read the input parameters
        inputFilesString = args[0];
        outputHeader = args[1];
        //numClasses = Integer.parseInt(args[2]);
        maxIterations = Integer.parseInt(args[2]);
        percentChangedThreshold = Double.parseDouble(args[3]);
        centroidMergeDist = Double.parseDouble(args[4]);
        if (!args[5].toLowerCase().contains("not specified")) {
            maxDist = Double.parseDouble(args[5]);
        }
        minimumAllowableClassSize = Integer.parseInt(args[6]);
        if (args[7].toLowerCase().contains("random")) {
            initializationMode = 1; //random positioning 
        } else {
            initializationMode = 0; //maximum dispersion along multi-dimensional diagonal
        }

        int[] clusterHistory = new int[maxIterations];
        double[] changeHistory = new double[maxIterations];
        
        try {
            
            // deal with the input images
            imageFiles = inputFilesString.split(";");
            numImages = imageFiles.length;
            images = new WhiteboxRasterInfo[numImages];
            imageMetaData = new double[numImages][3];
            for (i = 0; i < numImages; i++) {
                images[i] = new WhiteboxRasterInfo(imageFiles[i]);
                if (i == 0) {
                    nCols = images[i].getNumberColumns();
                    nRows = images[i].getNumberRows();
                    noData = images[i].getNoDataValue();
                } else {
                    if (images[i].getNumberColumns() != nCols
                            || images[i].getNumberRows() != nRows) {
                        showFeedback("All input images must have the same dimensions (rows and columns).");
                        return;
                    }
                }
                imageMetaData[i][0] = images[i].getNoDataValue();
                imageMetaData[i][1] = images[i].getMinimumValue();
                imageMetaData[i][2] = images[i].getMaximumValue();

            }

            numClasses = initialNumClasses;
            
            data = new double[numImages][];
            numPixelsInEachClass = new long[numImages];

            // now set up the output image
            WhiteboxRaster output = new WhiteboxRaster(outputHeader, "rw",
                    imageFiles[0], WhiteboxRaster.DataType.INTEGER, noData);
            output.setDataScale(DataScale.CATEGORICAL);
            output.setPreferredPalette("qual.pal");

            // initialize the class centres either along the diagonal or randomly
            if (initializationMode == 1) {
                Random generator = new Random();
                double range;
                for (a = 0; a < numClasses; a++) {
                    classCentre = new double[numImages];
                    for (i = 0; i < numImages; i++) {
                        range = imageMetaData[i][2] - imageMetaData[i][1];
                        classCentre[i] = imageMetaData[i][1] + generator.nextDouble() * range;
                    }
                    centres.add(classCentre);
                }
            } else {
                double range, spacing;
                for (a = 0; a < numClasses; a++) {
                    classCentre = new double[numImages];
                    for (i = 0; i < numImages; i++) {
                        range = imageMetaData[i][2] - imageMetaData[i][1];
                        spacing = range / numClasses;
                        classCentre[i] = imageMetaData[i][1] + spacing * a;
                    }
                    centres.add(classCentre);
                }
            }

            j = 0;
            whichClass = 0;
            do {
                if (j > 0) {
                    numClasses = classCentres.length; //centres.size();
                    
                    centres.clear();
                    for (a = 0; a < classCentres.length; a++) {
                        centres.add(classCentres[a]);
                    }
                            
                    ArrayList<Long> numPixels = new ArrayList<Long>();
                    for (i = 0; i < numPixelsInEachClass.length; i++) {
                        numPixels.add(numPixelsInEachClass[i]);
                    }
                    
                    
                    // Remove any empty classes or classes smaller than the minimumAllowableClassSize
                    boolean flag = true;
                    a = 0;
                    do {
                        if (numPixels.get(a) == 0) {
                            centres.remove(a);
                            numPixels.remove(a);
                            flag = true;
                            a = -1;
                        }
                        a++;
                        if (a >= numPixels.size()) {
                            flag = false;
                        }
                    } while (flag);
                    
                    // See if any of the class centroids are close enough to be merged.
                    
                    long numPixels1, numPixels2; 
                    do {
                        flag = false;
                        for (a = 0; a < centres.size(); a++) {
                            if (flag) {
                                break;
                            }
                            classCentre = centres.get(a);
                            numPixels1 = numPixels.get(a);
                            for (int b = a; b < centres.size(); b++) {
                                numPixels2 = numPixels.get(b);
                                if (b > a && numPixels1 > 0 && numPixels2 > 0) {
                                    double[] classCentre2 = centres.get(b);
                                    dist = 0;
                                    for (i = 0; i < numImages; i++) {
                                        dist += (classCentre[i] - classCentre2[i]) * (classCentre[i] - classCentre2[i]);
                                    }
                                    dist = Math.sqrt(dist);
                                    if (dist < centroidMergeDist) {
                                        // these two clusters should be merged
                                        double[] classCentre3 = new double[numImages];
                                        long totalPix = numPixels1 + numPixels2;
                                        double weight1 = (double)numPixels1 / totalPix;
                                        double weight2 = (double)numPixels2 / totalPix;

                                        for (int k = 0; k < numImages; k++) {
                                            classCentre3[k] = classCentre[k] * weight1 + classCentre2[k] * weight2;
                                        }
                                        centres.remove(Math.max(a, b));
                                        centres.remove(Math.min(a, b));
                                        centres.add(classCentre3);
                                        
                                        numPixels.remove(Math.max(a, b));
                                        numPixels.remove(Math.min(a, b));
                                        numPixels.add(totalPix);
                                        
                                        flag = true;
                                    }
                                    if (flag) {
                                        break; // once two have been merged, stop looking and start over.
                                    }
                                }
                            }
                        }
                        numClasses = centres.size();
                    } while (flag);
                    
                    // Remove any classes smaller than the minimumAllowableClassSize
                    flag = true;
                    a = 0;
                    do {
                        if (numPixels.get(a) < minimumAllowableClassSize) {
                            centres.remove(a);
                            numPixels.remove(a);
                            flag = true;
                            a = -1;
                        }
                        a++;
                        if (a >= numPixels.size()) {
                            flag = false;
                        }
                    } while (flag);

                }

                numClasses = centres.size();
                classCentres = new double[numClasses][numImages];
                for (a = 0; a < numClasses; a++) {
                    classCentre = centres.get(a);
                    classCentres[a] = classCentre.clone();                  
                }
                
                j++;
                // assign each pixel to a class
                updateProgress("Loop " + j, 1);
                double[][] classCentreData = new double[numClasses][numImages];
                numPixelsInEachClass = new long[numClasses];

                numPixelsChanged = 0;
                
                for (row = 0; row < nRows; row++) {
                    for (i = 0; i < numImages; i++) {
                        data[i] = images[i].getRowValues(row);
                    }
                    for (col = 0; col < nCols; col++) {
                        // check to see if the cell is a nodata value in any of the input images
                        isNoDataPixel = false;
                        for (i = 0; i < numImages; i++) {
                            if (data[i][col] == imageMetaData[i][0]) { 
                                isNoDataPixel = true; 
                                break;
                            }
                        }
                        if (!isNoDataPixel) {
                            if (!totalNumCellsCounted) {
                                totalNumCells++;
                            }
                            // calculate the squared distance to each of the centroids
                            // and assign the pixel the value of the nearest centroid.
                            minDist = Double.POSITIVE_INFINITY;
                            whichClass = unassignedClass;
                            for (a = 0; a < numClasses; a++) {
                                dist = 0;
                                for (i = 0; i < numImages; i++) {
                                    dist += (data[i][col] - classCentres[a][i]) * (data[i][col] - classCentres[a][i]);
                                }
                                if (dist < minDist && dist <= maxDist) {
                                    minDist = dist;
                                    whichClass = a;
                                }
                            }
                            // See if the assigned class has changed and if it has add it to the total changed cells.
                            // This is a criterion for stopping.
                            z = output.getValue(row, col);
                            if ((int)z != whichClass) {
                                numPixelsChanged++;
                                
                                // Assign the output pixel the class value
                                output.setValue(row, col, whichClass);
                            }
                            if (whichClass != unassignedClass) {
                                numPixelsInEachClass[whichClass]++;

                                for (i = 0; i < numImages; i++) {
                                    classCentreData[whichClass][i] += (data[i][col] - imageMetaData[i][1]);
                                }
                            }
                            
                        } else {
                            output.setValue(row, col, noData);
                        }
                    }
                    if (cancelOp) {
                        cancelOperation();
                        return;
                    }
                    progress = (int) (100f * row / (nRows - 1));
                    updateProgress("Loop " + j, progress);
                }
                totalNumCellsCounted = true;

                // Update the class centroids
                for (a = 0; a < numClasses; a++) {
                    if (numPixelsInEachClass[a] > 0) {
                        double[] newClassCentre = new double[numImages];
                        for (i = 0; i < numImages; i++) {
                            newClassCentre[i] = classCentreData[a][i] / numPixelsInEachClass[a] + imageMetaData[i][1];
                        }
                        for (i = 0; i < numImages; i++) {
                            classCentres[a][i] = newClassCentre[i];
                        }
                    }
                }
                
                percentChanged = (double)numPixelsChanged / totalNumCells * 100;
                clusterHistory[j - 1] = numClasses;
                changeHistory[j - 1] = percentChanged;
            } while ((percentChanged > percentChangedThreshold) && (j < maxIterations));

            // prepare the report
            double[] totalDeviations = new double[numClasses];
            int numberOfUnassignedPixels = 0;
            for (row = 0; row < nRows; row++) {
                for (i = 0; i < numImages; i++) {
                    data[i] = images[i].getRowValues(row);
                }
                for (col = 0; col < nCols; col++) {
                    isNoDataPixel = false;
                    for (i = 0; i < numImages; i++) {
                        if (data[i][col] == imageMetaData[i][0]) {
                            isNoDataPixel = true;
                            break;
                        }
                    }
                    if (!isNoDataPixel) {
                        whichClass = (int) (output.getValue(row, col));
                        if (whichClass != unassignedClass) {
                            dist = 0;
                            for (i = 0; i < numImages; i++) {
                                dist += (data[i][col] - classCentres[whichClass][i]) * (data[i][col] - classCentres[whichClass][i]);
                            }
                            totalDeviations[whichClass] += dist;
                        } else {
                            numberOfUnassignedPixels++;
                        }
                    } else {
                        output.setValue(row, col, noData);
                    }
                }
                if (cancelOp) {
                    cancelOperation();
                    return;
                }
                progress = (int) (100f * row / (nRows - 1));
                updateProgress("Loop " + j, progress);
            }
            
            double[] standardDeviations = new double[numClasses];
            for (a = 0; a < numClasses; a++) {
                standardDeviations[a] = Math.sqrt(totalDeviations[a] / (numPixelsInEachClass[a] - 1));
            }
            
            DecimalFormat df;
            df = new DecimalFormat("0.00");
            
            String retStr = "Modified k-Means Classification Report\n\n";
            
            retStr += "     \tCentroid Vector\n"; 
            retStr += "     \t";
            for (i = 0; i < numImages; i++) {
                retStr += "Image" + (i + 1) + "\t";
            }
            retStr += "SD\tPixels\t% Area\n";
            for (a = 0; a < numClasses; a++) {
                String str = "";
                for (i = 0; i < numImages; i++) {
                    str += df.format(classCentres[a][i]) + "\t";
                }
                        
                retStr += "Cluster " + a + "\t" + str + df.format(standardDeviations[a]) + "\t" + numPixelsInEachClass[a] + "\t" + df.format((double)numPixelsInEachClass[a] / totalNumCells * 100) + "\n";
            }
            retStr += "\n";
            retStr += "Number of unassigned pixels (class = -1): " + numberOfUnassignedPixels + "\n\n";
            
            
            for (i = 0; i < numImages; i++) {
                retStr += "Image" + (i + 1) + " = " + images[i].getShortHeaderFile() + "\n";
            }
            
            retStr += "\nCluster Centroid Distance Analysis:\n";
            for (a = 0; a < numClasses; a++) {
                retStr += "\tClus. " + a;
            }
            retStr += "\n";
            
            //double[][] centroidDistances = new double[numClasses][numClasses];
            for (a = 0; a < numClasses; a++) {
                retStr += "Cluster " + a;
                for (int b = 0; b < numClasses; b++) {
                    if (b >= a) {
                        dist = 0;
                        for (i = 0; i < numImages; i++) {
                            dist += (classCentres[a][i] - classCentres[b][i]) * (classCentres[a][i] - classCentres[b][i]);
                        }
                        retStr += "\t" + df.format(Math.sqrt(dist));
                    } else {
                        retStr += "\t";
                    }
                }
                retStr += "\n";
            }
            
            retStr += "\nCluster Merger History:\n";
            retStr += "Iteration\tNumber of Clusters\tPercent Changed\n";
            
            for (i = 0; i < maxIterations; i++) {
                if (clusterHistory[i] > 0) {
                    retStr += (i + 1) + "\t" + clusterHistory[i] + "\t" + changeHistory[i] + "\n";
                } else {
                    break;
                }
            }
            
            returnData(retStr);

            Dendrogram plot = new Dendrogram(classCentres, numPixelsInEachClass);
            returnData(plot);
            
            for (i = 0; i < numImages; i++) {
                images[i].close();
            }
            
            
            output.addMetadataEntry("Created by the "
                    + getDescriptiveName() + " tool.");
            output.addMetadataEntry("Created on " + new Date());
            output.close();

            // returning a header file string displays the image.
            returnData(outputHeader);

        } catch (OutOfMemoryError oe) {
            myHost.showFeedback("An out-of-memory error has occurred during operation.");
        } catch (Exception e) {
            myHost.showFeedback("An error has occurred during operation. See log file for details.");
            myHost.logException("Error in " + getDescriptiveName(), e);
        } finally {
            updateProgress("Progress: ", 0);
            // tells the main application that this process is completed.
            amIActive = false;
            myHost.pluginComplete();
        }
    }
//    // this is only used for debugging the tool
//    public static void main(String[] args) {
//        ModifiedKMeans mkm = new ModifiedKMeans();
//        args = new String[6];
////        args[0] = "/Users/johnlindsay/Documents/Teaching/GEOG3420/Winter 2012/Labs/Lab1/Data/LE70180302002142EDC00/band1 clipped.dep;/Users/johnlindsay/Documents/Teaching/GEOG3420/Winter 2012/Labs/Lab1/Data/LE70180302002142EDC00/band2 clipped.dep;/Users/johnlindsay/Documents/Teaching/GEOG3420/Winter 2012/Labs/Lab1/Data/LE70180302002142EDC00/band3 clipped.dep;/Users/johnlindsay/Documents/Teaching/GEOG3420/Winter 2012/Labs/Lab1/Data/LE70180302002142EDC00/band4 clipped.dep;/Users/johnlindsay/Documents/Teaching/GEOG3420/Winter 2012/Labs/Lab1/Data/LE70180302002142EDC00/band5 clipped.dep;";
////        args[1] = "/Users/johnlindsay/Documents/Teaching/GEOG3420/Winter 2012/Labs/Lab1/Data/LE70180302002142EDC00/tmp1.dep";
//        args[0] = "/Users/johnlindsay/Documents/Data/LandsatData/band1.dep;/Users/johnlindsay/Documents/Data/LandsatData/band2_cropped.dep;/Users/johnlindsay/Documents/Data/LandsatData/band3_cropped.dep;/Users/johnlindsay/Documents/Data/LandsatData/band4_cropped.dep;/Users/johnlindsay/Documents/Data/LandsatData/band5_cropped.dep";
//        args[1] = "/Users/johnlindsay/Documents/Data/LandsatData/tmp2.dep";
//        args[2] = "5"; // max iterations
//        args[3] = "2"; // changed pixels
//        args[4] = "30"; // centroid merge dist
//        args[5] = "diagonal"; // centroid initiation process
//        
//        mkm.setArgs(args);
//        mkm.run();
//        
//    }
}
