package github.com.kosmosec;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.sitemap.SiteMapFilter;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SiteMapDownloader implements BurpExtension
{

    @Override
    public void initialize(MontoyaApi montoyaApi)
    {
        montoyaApi.extension().setName("Site Map Downloader");
        montoyaApi.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider()
        {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event)
            {

                JMenuItem downloadAction = new JMenuItem("Download as ZIP");
                downloadAction.addActionListener(l -> {
                    // Determine the base request response from the context
                    HttpRequestResponse requestResponse = event.messageEditorRequestResponse().isPresent()
                            ? event.messageEditorRequestResponse().get().requestResponse()
                            : event.selectedRequestResponses().get(0);

                    // Use the URL from the request as the base URL for the folder tree
                    String baseUrl = requestResponse.request().url();

                    // Retrieve all site map entries matching the base URL prefix
                    List<HttpRequestResponse> requestResponseList = montoyaApi.siteMap().requestResponses(
                            SiteMapFilter.prefixFilter(baseUrl));

                    if (requestResponseList.isEmpty()) {
                        montoyaApi.logging().logToOutput("No items found for URL: " + baseUrl);
                        return;
                    }

                    // Prompt the user to choose the destination file for the ZIP archive
                    JFileChooser fc = new JFileChooser();
                    fc.setDialogTitle("Save Zipped Folder");
                    fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
                    int returnVal = fc.showSaveDialog(null);
                    if (returnVal != JFileChooser.APPROVE_OPTION) {
                        return;
                    }

                    File zipFile = fc.getSelectedFile();
                    if (!zipFile.getName().toLowerCase().endsWith(".zip")) {
                        zipFile = new File(zipFile.getAbsolutePath() + ".zip");
                    }

                    Set<String> addedDirectories = new HashSet<>();
                    Set<String> addedFiles = new HashSet<>();
                    String rootFolder = "";
                    String[] basePaths = requestResponse.request().pathWithoutQuery().split("/");
                    if (basePaths.length == 0) {
                        rootFolder = requestResponse.request().headerValue("Host");
                    } else {
                        rootFolder = basePaths[basePaths.length-1];
                    }
                    try (FileOutputStream fos = new FileOutputStream(zipFile);
                         ZipOutputStream zos = new ZipOutputStream(fos)) {

                        for (HttpRequestResponse rr : requestResponseList) {
                            if (rr.response() != null) {
                                String fullUrl = rr.request().url();
                                String relativePath = getRelativePath(baseUrl, fullUrl);
                                relativePath = rootFolder + (relativePath.startsWith("/") ? "": "/") + relativePath;
                                if (relativePath.endsWith("/")) {
                                    relativePath += "index.html";
                                } else {
                                    String fileName = new File(relativePath).getName();
                                    if (!fileName.contains(".")) {
                                        String contentType = getResponseContentType(rr);
                                        relativePath += contentType;
                                    }
                                }
                                addParentDirectories(relativePath, zos, addedDirectories, montoyaApi);

                                if (addedFiles.contains(relativePath)) {
                                    continue;
                                }
                                addedFiles.add(relativePath);
                                montoyaApi.logging().logToOutput("Adding file entry: " + relativePath);
                                ZipEntry fileEntry = new ZipEntry(relativePath);
                                zos.putNextEntry(fileEntry);

                                ByteArray content = rr.response().body();
                                zos.write(content.getBytes(), 0, content.length());
                                zos.closeEntry();
                            }
                        }
                    } catch (Exception e) {
                        montoyaApi.logging().logToError("Error creating zip file: " + e.getMessage());
                    }
                    montoyaApi.logging().logToOutput("Zipped file created: " + zipFile.getAbsolutePath());
                });

                return List.of(downloadAction);
            }
        });
    }

    private String getRelativePath(String baseUrl, String fileUrl) {
        try {
            URL base = new URL(baseUrl);
            URL file = new URL(fileUrl);
            String basePath = base.getPath();
            String filePath = file.getPath();

            if (filePath.startsWith(basePath)) {
                return filePath.substring(basePath.length());
            }
            return filePath;
        } catch (Exception e) {
            return "";
        }
    }

    private void addParentDirectories(String relativePath, ZipOutputStream zos, Set<String> addedDirectories,
                                      MontoyaApi montoyaApi) throws IOException {
        int lastSeparator = relativePath.lastIndexOf("/");
        if (lastSeparator > 0) {
            String directories = relativePath.substring(0, lastSeparator);
            String[] subDirs = directories.split("/");
            String currentPath = "";
            for (String dir : subDirs) {
                currentPath += dir + "/";
                if (!addedDirectories.contains(currentPath)) {
                    montoyaApi.logging().logToOutput("Adding directory entry: " + currentPath);
                    ZipEntry dirEntry = new ZipEntry(currentPath);
                    zos.putNextEntry(dirEntry);
                    zos.closeEntry();
                    addedDirectories.add(currentPath);
                }
            }
        }
    }

    private String getResponseContentType(HttpRequestResponse rr) {
        for (HttpHeader header : rr.response().headers()) {
            if (header.name().toLowerCase().startsWith("content-type")) {
                String[] parts = header.value().split("/", 2);
                if (!parts[1].isEmpty()) {
                    return ".".concat(parts[1].split(";")[0]);
                }
            }
        }
        return ".bin";
    }
}