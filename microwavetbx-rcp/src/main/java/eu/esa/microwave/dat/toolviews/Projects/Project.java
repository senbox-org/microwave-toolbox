/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.microwave.dat.toolviews.Projects;

import com.bc.ceres.swing.progress.ProgressMonitorSwingWorker;
import eu.esa.microwave.dat.dialogs.ProductSetDialog;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.dataio.dimap.DimapProductConstants;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductManager;
import org.esa.snap.core.datamodel.ProductNodeList;
import org.esa.snap.core.dataop.downloadable.XMLSupport;
import org.esa.snap.core.util.io.FileUtils;
import org.esa.snap.core.util.io.SnapFileFilter;
import org.esa.snap.engine_utilities.gpf.CommonReaders;
import org.esa.snap.engine_utilities.util.ProductFunctions;
import org.esa.snap.engine_utilities.util.ResourceUtils;
import org.esa.snap.graphbuilder.rcp.dialogs.GraphBuilderDialog;
import org.esa.snap.graphbuilder.rcp.dialogs.PromptDialog;
import org.esa.snap.rcp.SnapApp;
import org.esa.snap.rcp.actions.file.OpenProductAction;
import org.esa.snap.rcp.actions.file.SaveProductAsAction;
import org.esa.snap.rcp.actions.file.WriteProductOperation;
import org.esa.snap.rcp.session.OpenSessionAction;
import org.esa.snap.rcp.session.SaveSessionAction;
import org.esa.snap.rcp.util.Dialogs;
import org.esa.snap.ui.ModelessDialog;
import org.esa.snap.ui.NewProductDialog;
import org.esa.snap.ui.product.ProductSubsetDialog;
import org.jdom2.Attribute;
import org.jdom2.Content;
import org.jdom2.Document;
import org.jdom2.Element;
import org.netbeans.api.progress.ProgressUtils;
import org.openide.windows.TopComponent;
import org.openide.windows.WindowManager;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.Timer;

/**
 * A Project helps to organize your data by storing all your work in one folder.
 * User: lveci
 * Date: Jan 23, 2008
 */
public class Project extends Observable {

    private final static Project _instance = new Project();
    private final List<Listener> listeners = new ArrayList<>();

    private File projectFolder = null;
    private File projectFile = null;
    private ProductManager.Listener productManagerListener = null;
    private ProjectSubFolder projectSubFolders = null;
    private final static boolean SAVE_PROJECT = true;
    private final Timer timer = new Timer();

    private final static SnapFileFilter projectFileFilter = new SnapFileFilter("Project", new String[]{".xml"}, "SNAP project files");
    private final static String LAST_PROJECT_DIR_KEY = "snap.lastProjectDir";

    /**
     * @return The unique instance of this class.
     */
    public static Project instance() {
        return _instance;
    }

    @Override
    public void finalize() throws Throwable {
        timer.cancel();
        super.finalize();
    }

    void notifyEvent(boolean saveProject) {
        setChanged();
        notifyObservers();
        clearChanged();
        if (saveProject)
            SaveProject();

        for (Listener listener : listeners) {
            listener.projectChanged();
        }
    }

    private static void showProjectsView() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                final TopComponent window = WindowManager.getDefault().findTopComponent("ProjectsToolView");
                if (window != null) {
                    window.open();
                    window.requestActive();
                }
            }
        });
    }

    public void CreateNewProject() {
        File file = Dialogs.requestFileForOpen("Create Project", false, projectFileFilter, LAST_PROJECT_DIR_KEY);

        if (file != null) {
            showProjectsView();

            final String prjName = file.getName();
            final String folderName = FileUtils.getFilenameWithoutExtension(prjName);
            final File prjFolder = new File(file.getParentFile(), folderName);
            if (!prjFolder.exists())
                prjFolder.mkdir();
            final File newProjectFile = new File(prjFolder, prjName);

            initProject(newProjectFile);
            addExistingOpenedProducts();
            notifyEvent(SAVE_PROJECT);
        }
    }

    private void addExistingOpenedProducts() {
        final ProductManager prodman = SnapApp.getDefault().getProductManager();
        final int numProducts = prodman.getProductCount();
        for (int i = 0; i < numProducts; ++i) {
            addProductLink(prodman.getProduct(i));
        }
    }

    private static boolean findSubFolders(final File currentFolder, final ProjectSubFolder projSubFolder) {
        final File[] files = currentFolder.listFiles();
        boolean hasProducts = false;
        if (files == null) return false;

        for (File f : files) {
            if (f.isDirectory()) {
                if (!f.getName().endsWith(DimapProductConstants.DIMAP_DATA_DIRECTORY_EXTENSION)) {
                    final ProjectSubFolder newProjFolder = projSubFolder.addSubFolder(f.getName());

                    if (findSubFolders(f, newProjFolder))
                        hasProducts = true;
                    //else if(!newProjFolder.isCreatedByUser())
                    //projSubFolder.removeSubFolder(newProjFolder);
                }
            } else if (!projSubFolder.containsFile(f)) {
                boolean found = false;
                final ProjectSubFolder.FolderType folderType = projSubFolder.getFolderType();
                if (folderType == ProjectSubFolder.FolderType.PRODUCT) {
                    found = ProductFunctions.isValidProduct(f);
                } else if (folderType == ProjectSubFolder.FolderType.PRODUCTSET ||
                        folderType == ProjectSubFolder.FolderType.GRAPH) {
                    found = f.getName().toLowerCase().endsWith(".xml");
                }

                if (found) {
                    hasProducts = true;
                    final ProjectFile newFile = new ProjectFile(f, f.getName());
                    boolean added = projSubFolder.addFile(newFile);

                    if (added) {
                        newFile.setFolderType(folderType);
                    }
                }
            }
        }
        return hasProducts;
    }

    public File getProjectFolder() {
        return projectFolder;
    }

    public File getProjectFile() {
        return projectFile;
    }

    String getProjectName() {
        final String name = projectFile.getName();
        if (name.endsWith(".xml"))
            return name.substring(0, name.length() - 4);
        return name;
    }

    protected void initProject(final File file) {
        if (productManagerListener == null) {
            productManagerListener = new ProductManager.Listener() {
                @Override
                public void productAdded(ProductManager.Event event) {
                    if (projectSubFolders == null) return;
                    addProductLink(event.getProduct());
                    notifyEvent(SAVE_PROJECT);
                }

                @Override
                public void productRemoved(ProductManager.Event event) {
                    Product product = event.getProduct();
                    //if (getSelectedProduct() == product) {
                    //    setSelectedProduct(product);
                    //}
                    //setProducts(VisatApp.getApp());
                }
            };
            SnapApp.getDefault().getProductManager().addListener(productManagerListener);
        }

        projectFile = file;
        projectFolder = file.getParentFile();

        projectSubFolders = new ProjectSubFolder(projectFolder, getProjectName(), false,
                                                 ProjectSubFolder.FolderType.ROOT);
        projectSubFolders.setRemoveable(false);

        final ProjectSubFolder productSetsFolder = new ProjectSubFolder(
                new File(projectFolder, "ProductSets"), "ProductSets", true, ProjectSubFolder.FolderType.PRODUCTSET);
        projectSubFolders.addSubFolder(productSetsFolder);
        productSetsFolder.setRemoveable(false);

        final ProjectSubFolder graphsFolder = new ProjectSubFolder(
                new File(projectFolder, "Graphs"), "Graphs", true, ProjectSubFolder.FolderType.GRAPH);
        projectSubFolders.addSubFolder(graphsFolder);
        graphsFolder.setRemoveable(false);

        final ProjectSubFolder productLinksFolder = projectSubFolders.addSubFolder("External Product Links");
        productLinksFolder.setRemoveable(false);
        productLinksFolder.setFolderType(ProjectSubFolder.FolderType.PRODUCT);

        final ProjectSubFolder importedFolder = new ProjectSubFolder(
                new File(projectFolder, "Imported Products"), "Imported Products", true,
                ProjectSubFolder.FolderType.PRODUCT);
        projectSubFolders.addSubFolder(importedFolder);
        importedFolder.setRemoveable(false);

        final ProjectSubFolder processedFolder = new ProjectSubFolder(
                new File(projectFolder, "Processed Products"), "Processed Products", true,
                ProjectSubFolder.FolderType.PRODUCT);
        projectSubFolders.addSubFolder(processedFolder);
        processedFolder.setRemoveable(false);

        refreshProjectTree();

        final String[] defaultFolders = getDefaultProjectFolders();
        for (String folderName : defaultFolders) {
            final ProjectSubFolder newFolder = processedFolder.addSubFolder(folderName);
            newFolder.setCreatedByUser(true);
        }

        SnapApp.getDefault().getPreferences().put(
                SaveProductAsAction.PREFERENCES_KEY_LAST_PRODUCT_DIR, processedFolder.getPath().getAbsolutePath());

        // start refresh timer for any outside changes to project folder
        startUpdateTimer();
    }

    private static String[] getDefaultProjectFolders() {
        String defaultProjectFolders = System.getProperty("defaultProjectFolders");
        if (defaultProjectFolders == null) {
            defaultProjectFolders = "Calibrated Products, Coregistered Products, Orthorectified Products";
        }

        final List<String> folderNames = new ArrayList<>(5);
        final StringTokenizer st = new StringTokenizer(defaultProjectFolders, ",");
        int length = st.countTokens();
        for (int i = 0; i < length; i++) {
            folderNames.add(st.nextToken().trim());
        }
        return folderNames.toArray(new String[0]);
    }

    private void startUpdateTimer() {

        timer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                if (IsProjectOpen()) {
                    if (refreshProjectTree())
                        notifyEvent(SAVE_PROJECT);
                }
            }
        }, 2000, 1000 * 5);
    }

    private void addProductLink(final Product product) {
        final File productFile = product.getFileLocation();
        if (productFile == null)
            return;
        if (projectSubFolders.containsFile(productFile))
            return;

        refreshProjectTree();
        if (projectSubFolders.containsFile(productFile))
            return;

        final ProjectSubFolder productLinksFolder = projectSubFolders.addSubFolder("External Product Links");
        ProjectSubFolder destFolder = productLinksFolder;
        final String[] formats = product.getProductReader().getReaderPlugIn().getFormatNames();
        if (formats.length > 0)
            destFolder = productLinksFolder.addSubFolder(formats[0]);

        final ProjectFile newFile = new ProjectFile(productFile, product.getName());
        destFolder.addFile(newFile);
        newFile.setFolderType(ProjectSubFolder.FolderType.PRODUCT);
    }

    public boolean refreshProjectTree() {
        boolean found = false;
        final ProjectSubFolder productSetsFolder = projectSubFolders.findFolder("ProductSets");
        if (findSubFolders(productSetsFolder.getPath(), productSetsFolder))
            found = true;
        pruneNonExistantFiles(productSetsFolder);
        final ProjectSubFolder graphsFolder = projectSubFolders.findFolder("Graphs");
        if (findSubFolders(graphsFolder.getPath(), graphsFolder))
            found = true;
        pruneNonExistantFiles(graphsFolder);
        final ProjectSubFolder importedFolder = projectSubFolders.findFolder("Imported Products");
        if (findSubFolders(importedFolder.getPath(), importedFolder))
            found = true;
        pruneNonExistantFiles(importedFolder);
        final ProjectSubFolder processedFolder = projectSubFolders.findFolder("Processed Products");
        if (findSubFolders(processedFolder.getPath(), processedFolder))
            found = true;
        pruneNonExistantFiles(processedFolder);
        return found;
    }

    private static void pruneNonExistantFiles(final ProjectSubFolder projSubFolder) {
        // check for files to remove
        final ProjectFile[] fileList = projSubFolder.getFileList().toArray(new ProjectFile[0]);
        for (ProjectFile projFile : fileList) {
            final File f = projFile.getFile();
            if (!f.exists() || f.getName().endsWith(DimapProductConstants.DIMAP_DATA_DIRECTORY_EXTENSION)) {
                projSubFolder.removeFile(f);
            }
        }
        for (ProjectSubFolder subFolder : projSubFolder.getSubFolders()) {
            pruneNonExistantFiles(subFolder);
        }
    }

    public void createNewFolder(final ProjectSubFolder subFolder) {
        final PromptDialog dlg = new PromptDialog("New Folder", "Name", "", PromptDialog.TYPE.TEXTFIELD);
        dlg.show();
        if (dlg.IsOK()) {
            try {
                final ProjectSubFolder newFolder = subFolder.addSubFolder(dlg.getValue("Name"));
                newFolder.setCreatedByUser(true);
                if (subFolder == projectSubFolders || subFolder.isPhysical())
                    newFolder.setPhysical(true);
                notifyEvent(SAVE_PROJECT);
            } catch (Exception ex) {
                Dialogs.showError(ex.getMessage());
            }
        }
    }

    public void createNewProductSet(final ProjectSubFolder subFolder) {
        final String name = "ProductSet" + (subFolder.getFileList().size() + 1);
        final ProductSet prodSet = new ProductSet(new File(subFolder.getPath(), name));
        final ProductSetDialog dlg = new ProductSetDialog("New ProductSet", prodSet);
        dlg.show();
        if (dlg.IsOK()) {
            final ProjectFile newFile = new ProjectFile(prodSet.getFile(), prodSet.getName());
            newFile.setFolderType(ProjectSubFolder.FolderType.PRODUCTSET);
            subFolder.addFile(newFile);
            notifyEvent(SAVE_PROJECT);
            refreshProjectTree();
        }
    }

    public static void createNewGraph(final ProjectSubFolder subFolder) {
        final ModelessDialog dialog = new GraphBuilderDialog(SnapApp.getDefault().getAppContext(), "Graph Builder", "graph_builder");
        dialog.show();
    }

    public static void openFile(final ProjectSubFolder parentFolder, final File file) {
        if (parentFolder.getFolderType() == ProjectSubFolder.FolderType.PRODUCTSET) {
            ProductSet.OpenProductSet(file);
        } else if (parentFolder.getFolderType() == ProjectSubFolder.FolderType.GRAPH) {
            final GraphBuilderDialog dialog = new GraphBuilderDialog(SnapApp.getDefault().getAppContext(), "Graph Builder", "graph_builder");
            dialog.show();
            dialog.loadGraph(file);
        } else if (parentFolder.getFolderType() == ProjectSubFolder.FolderType.PRODUCT) {

            final OpenProductAction open = new OpenProductAction();
            open.setFile(file);
            open.execute();
        }
    }

    public static void openSubset(final ProjectSubFolder parentFolder, final File prodFile) {

        try {
            final Product product = CommonReaders.readProduct(prodFile);
            if (product != null) {
                final Product subsetProduct = getProductSubset(product);
                if (subsetProduct != null)
                    SnapApp.getDefault().getProductManager().addProduct(subsetProduct);
            }
        } catch (Exception e) {
            Dialogs.showError(e.getMessage());
        }
    }

    public void importSubset(final ProjectSubFolder parentFolder, final File prodFile) {
        final ProductReader reader = ProductIO.getProductReaderForInput(prodFile);
        if (reader != null) {
            final ProjectSubFolder importedFolder = projectSubFolders.findFolder("Imported Products");
            try {
                final Product product = reader.readProductNodes(prodFile, null);

                final Product subsetProduct = getProductSubset(product);
                if (subsetProduct != null) {
                    final File destFile = new File(importedFolder.getPath(), subsetProduct.getName());
                    writeProduct(subsetProduct, destFile);
                }
            } catch (Exception e) {
                Dialogs.showError("Unable to import product:" + e.getMessage());
            }
        }
    }

    private static Product getProductSubset(final Product product) {
        if (product != null) {
            final Frame mainFrame = SnapApp.getDefault().getMainFrame();
            final ProductSubsetDialog productSubsetDialog = new ProductSubsetDialog(mainFrame, product);
            if (productSubsetDialog.show() == ProductSubsetDialog.ID_OK) {
                final ProductNodeList<Product> products = new ProductNodeList<>();
                products.add(product);
                final NewProductDialog newProductDialog = new NewProductDialog(mainFrame, products, 0, true);
                newProductDialog.setSubsetDef(productSubsetDialog.getProductSubsetDef());
                if (newProductDialog.show() == NewProductDialog.ID_OK) {
                    final Product subsetProduct = newProductDialog.getResultProduct();
                    if (subsetProduct == null || newProductDialog.getException() != null) {
                        Dialogs.showError("The product subset could not be created:\n" +
                                                  newProductDialog.getException().getMessage());
                    } else {
                        return subsetProduct;
                    }
                }
            }
        }
        return null;
    }

    private static class ImportProducts extends ProgressMonitorSwingWorker {
        final File[] productFilesToOpen;
        final ProjectSubFolder importedFolder;

        ImportProducts(final File[] productFilesToOpen, final ProjectSubFolder importedFolder) {
            super(SnapApp.getDefault().getMainFrame(), "Writing...");
            this.productFilesToOpen = productFilesToOpen;
            this.importedFolder = importedFolder;
        }

        @Override
        protected Object doInBackground(com.bc.ceres.core.ProgressMonitor pm) throws Exception {
            pm.beginTask("Importing", productFilesToOpen.length);
            if (importedFolder.getFolderType() == ProjectSubFolder.FolderType.PRODUCT) {
                for (File prodFile : productFilesToOpen) {
                    final ProductReader reader = ProductIO.getProductReaderForInput(prodFile);
                    if (reader != null) {
                        try {
                            final Product product = reader.readProductNodes(prodFile, null);
                            if (product != null) {
                                // special case for WSS products
                                if (product.getProductType().equals("ASA_WSS_1P")) {
                                    throw new Exception("WSS products need to be debursted before saving as DIMAP");
                                }
                                final File destFile = new File(importedFolder.getPath(), product.getName());

                                WriteProductOperation operation = new WriteProductOperation(product, destFile, "BEAM-DIMAP", false);
                                ProgressUtils.runOffEventThreadWithProgressDialog(operation,
                                                                                  "Writing...",
                                                                                  operation.getProgressHandle(),
                                                                                  true,
                                                                                  50,
                                                                                  1000);

                                SnapApp.getDefault().setStatusBarMessage("");
                            }
                        } catch (Exception e) {
                            Dialogs.showError(e.getMessage());
                        }
                    }
                    pm.worked(1);
                }
            }
            pm.done();
            return true;
        }
    }

    private void writeProduct(final Product product, final File destFile) {

        final SwingWorker worker = new SwingWorker() {
            @Override
            protected Object doInBackground() throws Exception {

                WriteProductOperation operation = new WriteProductOperation(product, destFile, "BEAM-DIMAP", false);
                ProgressUtils.runOffEventThreadWithProgressDialog(operation,
                                                                  "Writing...",
                                                                  operation.getProgressHandle(),
                                                                  true,
                                                                  50,
                                                                  1000);
                return null;
            }

            @Override
            public void done() {
                refreshProjectTree();
                notifyEvent(SAVE_PROJECT);
            }
        };
        worker.execute();
    }

    public void ImportFileList(final File[] productFilesToOpen) {
        if (!IsProjectOpen()) {
            CreateNewProject();
        }
        final ProjectSubFolder importedFolder = projectSubFolders.findFolder("Imported Products");

        final ImportProducts worker = new ImportProducts(productFilesToOpen, importedFolder);
        worker.execute();

        refreshProjectTree();
        notifyEvent(SAVE_PROJECT);
    }

    public void deleteFolder(final ProjectSubFolder parentFolder, final ProjectSubFolder subFolder) {
        parentFolder.removeSubFolder(subFolder);
        notifyEvent(SAVE_PROJECT);
    }

    public void clearFolder(final ProjectSubFolder subFolder) {
        subFolder.clear();
        notifyEvent(SAVE_PROJECT);
    }

    public void renameFolder(final ProjectSubFolder subFolder) {
        final PromptDialog dlg = new PromptDialog("Rename Folder", "Name", "", PromptDialog.TYPE.TEXTFIELD);
        dlg.show();
        if (dlg.IsOK()) {
            try {
                subFolder.renameTo(dlg.getValue("Name"));
                notifyEvent(SAVE_PROJECT);
            } catch (Exception ex) {
                Dialogs.showError(ex.getMessage());
            }
        }
    }

    public void removeFile(final ProjectSubFolder parentFolder, final File file) {
        parentFolder.removeFile(file);
        if (parentFolder.getFolderType() == ProjectSubFolder.FolderType.PRODUCTSET ||
                parentFolder.getFolderType() == ProjectSubFolder.FolderType.GRAPH)
            file.delete();
        else if (parentFolder.getFolderType() == ProjectSubFolder.FolderType.PRODUCT) {
            if (file.getName().endsWith(DimapProductConstants.DIMAP_HEADER_FILE_EXTENSION)) {
                final String pathStr = file.getAbsolutePath();
                final File dataDir = new File(pathStr.substring(0, pathStr.length() - 4) + DimapProductConstants.DIMAP_DATA_DIRECTORY_EXTENSION);
                if (dataDir.exists()) {
                    ResourceUtils.deleteFile(dataDir);
                    file.delete();
                }
            } else {
                file.delete();
            }
        }
        notifyEvent(SAVE_PROJECT);
    }

    public ProjectSubFolder getProjectSubFolders() {
        return projectSubFolders;
    }

    public void CloseProject() {
        projectSubFolders = null;
        notifyEvent(false);
    }

    public boolean IsProjectOpen() {
        return projectSubFolders != null;
    }

    public void SaveProjectAs() {
        File file = Dialogs.requestFileForSave("Save Project", false, projectFileFilter, ".xml", getProjectName(), null, LAST_PROJECT_DIR_KEY);
        if (file == null) return;

        projectFile = file;
        projectFolder = file.getParentFile();

        SaveProject();
    }

    public void SaveProject() {
        if (projectSubFolders == null)
            return;

        final Element root = new Element("Project");
        root.setAttribute("name", getProjectName());
        final Document doc = new Document(root);

        final List<ProjectSubFolder> subFolders = projectSubFolders.getSubFolders();
        for (Object subFolder : subFolders) {
            final ProjectSubFolder folder = (ProjectSubFolder) subFolder;
            final Element elem = folder.toXML();
            root.addContent(elem);
        }

        try {
            XMLSupport.SaveXML(doc, projectFile.getAbsolutePath());

            saveSession(projectFile);
        } catch (IOException e) {
            Dialogs.showError("Unable to save project: " + e.getMessage());
        }
    }

    public void LoadProject() {

        File file = Dialogs.requestFileForOpen("Load Project", false, projectFileFilter, LAST_PROJECT_DIR_KEY);
        if (file == null) return;

        LoadProject(file);
    }

    public void LoadProject(final File file) {

        showProjectsView();

        initProject(file);

        Document doc;
        try {
            doc = XMLSupport.LoadXML(file.getAbsolutePath());
        } catch (IOException e) {
            Dialogs.showError("Unable to load " + file.toString() + ": " + e.getMessage());
            return;
        }

        final List<ProjectSubFolder> folderList = new ArrayList<>();
        final List<ProjectFile> prodList = new ArrayList<>();

        final Element root = doc.getRootElement();

        final List<Content> children = root.getContent();
        for (Object aChild : children) {
            if (aChild instanceof Element) {
                final Element child = (Element) aChild;
                if (child.getName().equals("subFolder")) {
                    final Attribute attrib = child.getAttribute("name");
                    final ProjectSubFolder subFolder = projectSubFolders.addSubFolder(attrib.getValue());
                    subFolder.fromXML(child, folderList, prodList);
                }
            }
        }

        loadProducts(folderList, prodList);

        loadSession(file);

        notifyEvent(false);
    }

    private static void loadProducts(final List<ProjectSubFolder> folderList,
                                     final List<ProjectFile> prodList) {

        final ProgressMonitorSwingWorker worker = new ProgressMonitorSwingWorker(SnapApp.getDefault().getMainFrame(), "Opening Project") {
            @Override
            protected Object doInBackground(com.bc.ceres.core.ProgressMonitor pm) throws Exception {
                pm.beginTask("Opening Project...", prodList.size());
                try {
                    for (int i = 0; i < prodList.size(); ++i) {

                        final ProjectSubFolder subFolder = folderList.get(i);
                        final ProjectFile projFile = prodList.get(i);
                        final File prodFile = projFile.getFile();

                        if (ProductFunctions.isValidProduct(prodFile)) {
                            subFolder.addFile(projFile);
                        }
                        pm.worked(1);
                    }
                } finally {
                    pm.done();
                }
                return null;
            }
        };
        worker.executeWithBlocking();
    }

    private static void saveSession(final File projectFile) {
        File sessionFile = FileUtils.exchangeExtension(projectFile, ".session.snap");
        SaveSessionAction sessionAction = new SaveSessionAction();
        sessionAction.saveSessionAsQuitely(sessionFile);
    }

    private static void loadSession(final File projectFile) {
        File sessionFile = FileUtils.exchangeExtension(projectFile, ".session.snap");
        if (sessionFile.exists()) {
            OpenSessionAction sessionAction = new OpenSessionAction();
            sessionAction.openSession(sessionFile);
        }
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public interface Listener {
        void projectChanged();
    }
}
