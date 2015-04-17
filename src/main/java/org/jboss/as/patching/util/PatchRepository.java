/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.patching.util;

import static org.jboss.as.patching.IoUtils.copyStream;
import static org.jboss.as.patching.IoUtils.safeClose;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.patching.IoUtils;
import org.jboss.as.patching.PatchingException;
import org.jboss.as.patching.logging.PatchLogger;
import org.jboss.as.patching.metadata.Identity;
import org.jboss.as.patching.metadata.Patch;
import org.jboss.as.patching.metadata.Identity.IdentityUpgrade;
import org.jboss.as.patching.metadata.Patch.PatchType;
import org.jboss.as.patching.metadata.PatchElement;
import org.jboss.as.patching.metadata.PatchXml;

/**
 * Patch repository.
 *
 * <pre>
 * <code>
 * ROOT
 * |-- layers
 * |   |-- layer1
 * |   |   |-- patches
 * |   |   |   |-- patchElementId
 * |   |   |   |   |-- element.xml
 * |   |   |   |   |-- target-identity.txt
 * |   |   |   |   `-- FS-tree content
 * |   |   |   ...
 * |   |   |   `-- patch-patchElementId
 * |   |   |       |-- element.xml
 * |   |   |       |-- target-identity.txt
 * |   |   |       `-- FS-tree content
 * |   |   |
 * |   |   `-- updates
 * |   |       |-- patchElementId
 * |   |       |   |-- element.xml
 * |   |       |   |-- target-identity.txt
 * |   |       |   `-- FS-tree content
 * |   |       ...
 * |   |       `-- patchElementId
 * |   |           |-- element.xml
 * |   |           |-- target-identity.txt
 * |   |           `-- FS-tree content
 * |   ...
 * |   `-- layerN
 * |       |-- patches
 * |       |   |-- patchElementId
 * |       |   |   |-- element.xml
 * |       |   |   |-- target-identity.txt
 * |       |   |   `-- FS-tree content
 * |       |   ...
 * |       |   `-- patch-patchElementId
 * |       |       |-- element.xml
 * |       |       |-- target-identity.txt
 * |       |       `-- FS-tree content
 * |       |
 * |       `-- updates
 * |           |-- patchElementId
 * |           |   |-- element.xml
 * |           |   |-- target-identity.txt
 * |           |   `-- FS-tree content
 * |           ...
 * |           `-- patchElementId
 * |               |-- element.xml
 * |               |-- target-identity.txt
 * |               `-- FS-tree content
 * |
 * |-- identity-name-version
 * |   |-- patches
 * |   |   |-- patchId
 * |   |   |   |-- elements.txt
 * |   |   |   `-- misc content
 * |   |   ...
 * |   |   `-- patchId
 * |   |   |   |-- elements.txt
 * |   |       `-- misc content
 * |   `-- updates
 * |       |-- updateId
 * |       |   |-- update.txt
 * |       |   |-- elements.txt
 * |       |   `-- misc content
 * |       ...
 * |       `-- updateId
 * |           |-- update.txt
 * |           |-- elements.txt
 * |           `-- misc content
 * ...
 * `-- identity-name-version
 *     |-- patches
 *     |   |-- patchId
 *     |   |   |-- elements.txt
 *     |   |   `-- misc content
 *     |   ...
 *     |   `-- patchId
 *     |       |-- elements.txt
 *     |       `-- misc content
 *     `-- updates
 *         |-- updateId
 *         |   |-- update.txt
 *         |   |-- elements.txt
 *         |   `-- misc content
 *         ...
 *         `-- updateId
 *             |-- update.txt
 *             |-- elements.txt
 *             `-- misc content
 * </code>
 * </pre>
 *
 * @author Alexey Loubyansky
 */
public class PatchRepository {

    public static final String ELEMENT_XML = "element.xml";
    public static final String ELEMENTS_TXT = "elements.txt";
    public static final String LAYERS = "layers";
    public static final String MISC_FILES_XML = "misc-files.xml";
    public static final String PATCH_ = "patch-";
    public static final String PATCHES = "patches";
    public static final String TARGET_IDENTITY_TXT = "target-identity.txt";
    public static final String TEMPLATE_PATCH_XML = "template-patch.xml";
    public static final String UPDATES = "updates";
    public static final String UPDATE_TXT = "update.txt";
    private static final String LN = System.getProperty("line.separator");

    private static final File TMP_DIR = new File(System.getProperty("java.io.tmpdir"));

    public static PatchRepository create(File root) {
        return new PatchRepository(root);
    }

    private final File root;

    private PatchRepository(File root) {
        if (root == null) {
            throw new IllegalArgumentException("root is null");
        }
        this.root = root;
    }

    public File getIdentityDir(String name, String version) {
        if (name == null) {
            throw new IllegalArgumentException("name is null");
        }
        if (version == null) {
            throw new IllegalArgumentException("version is null");
        }
        return new File(root, getIdentityDirName(name, version));
    }

    public void addPatch(File patchFile) throws PatchingException {

        if (patchFile == null) {
            throw new IllegalArgumentException("patchFile is null");
        }

        if (!patchFile.exists()) {
            throw new PatchingException(PatchLogger.ROOT_LOGGER.fileDoesNotExist(patchFile.getAbsolutePath()));
        }
        if (patchFile.isDirectory()) {
            throw new PatchingException(PatchLogger.ROOT_LOGGER.fileIsADirectory(patchFile.getAbsolutePath()));
        }

        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(patchFile);

            final String patchXml = getPatchXml(zipFile);
            final Patch patch = parsePatchXml(patchXml);

            final File patchDir = addPatchDir(patch, patchXml);

            final Map<String, PatchElement> elements = new HashMap<String, PatchElement>();
            for (PatchElement e : patch.getElements()) {
                elements.put(e.getId(), e);
            }

            final Enumeration<? extends ZipEntry> entries = zipFile.entries();
            String rootDirEntry = null;
            File currentDir = null;
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                if (rootDirEntry != null && entry.getName().startsWith(rootDirEntry)) {
                    if (!entry.isDirectory()) {
                        final File targetFile = new File(currentDir, entry.getName());
                        ensureDir(targetFile.getParentFile());
                        try {
                            IoUtils.copyStreamAndClose(zipFile.getInputStream(entry), new FileOutputStream(targetFile));
                        } catch (IOException e) {
                            throw new PatchingException("Failed to expand " + entry.getName() + " to "
                                    + targetFile.getAbsolutePath(), e);
                        }
                    }
                } else {
                    if (entry.isDirectory()) {
                        rootDirEntry = entry.getName();
                        final String name = rootDirEntry.substring(0, rootDirEntry.length() - 1);
                        final PatchElement element = elements.get(name);
                        if (element != null) {
                            currentDir = addElementDir(patch, element, patchXml);
                        } else if (name.equals(patch.getPatchId())) {
                            currentDir = patchDir;
                        } else {
                            // non-patch content
                            currentDir = patchDir;
                        }
                    } else {
                        rootDirEntry = null;
                        currentDir = null;
                        if (!entry.getName().equals(PatchXml.PATCH_XML)) {
                            final File targetFile = new File(patchDir, entry.getName());
                            try {
                                IoUtils.copyStreamAndClose(zipFile.getInputStream(entry), new FileOutputStream(targetFile));
                            } catch (IOException e) {
                                throw new PatchingException("Failed to expand " + entry.getName() + " to "
                                        + targetFile.getAbsolutePath(), e);
                            }
                        }
                    }
                }
            }
        } catch (ZipException e) {
            throw new PatchingException("Failed to open " + patchFile.getAbsolutePath(), e);
        } catch (IOException e) {
            throw new PatchingException("Failed to add " + patchFile.getAbsolutePath(), e);
        } finally {
            IoUtils.safeClose(zipFile);
        }
    }

    public boolean hasPatches(String identityName, String identityVersion) throws PatchingException {
        final File f = new File(getIdentityDir(identityName, identityVersion), PATCHES);
        if (!f.exists()) {
            return false;
        }
        if (f.isFile()) {
            throw new PatchingException(f.getAbsolutePath() + " is not a directory");
        }
        return f.list().length > 0;
    }

    public List<Patch> getPatchesInfo(String identityName, String identityVersion) throws PatchingException {

        final File patchesDir = new File(getIdentityDir(identityName, identityVersion), PATCHES);
        if (!patchesDir.exists()) {
            return Collections.emptyList();
        }
        if (patchesDir.isFile()) {
            throw new PatchingException(patchesDir.getAbsolutePath() + " is not a directory");
        }

        final File[] children = patchesDir.listFiles();
        final List<Patch> patches = new ArrayList<Patch>(children.length);
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            patches.add(parsePatchXml(getPatchXml(identityName, identityVersion, child.getName(), false)));
        }
        return patches;
    }

    public boolean hasUpdate(String identityName, String identityVersion) throws PatchingException {
        final File f = new File(getIdentityDir(identityName, identityVersion), UPDATES);
        if (!f.exists()) {
            return false;
        }
        if (f.isFile()) {
            throw new PatchingException(f.getAbsolutePath() + " is not a directory");
        }
        return f.list().length > 0;
    }

    public File bundlePatches(String identityName, String identityVersion, File targetDir) throws PatchingException {

        if (targetDir == null) {
            throw new IllegalArgumentException("targetDir is null");
        }

        final PatchBundleBuilder bundleBuilder = PatchBundleBuilder.create();
        bundlePatches(identityName, identityVersion, bundleBuilder);

        ensureDir(targetDir);
        final File targetFile = new File(targetDir, getIdentityDirName(identityName, identityVersion) + "-" + PATCHES + ".zip");
        bundleBuilder.build(targetFile, true);
        return targetFile;
    }

    public void getUpdateToNext(String identityName, String identityVersion, boolean includePatches, File targetFile)
            throws PatchingException {

        final File update = getUpdateOnly(identityName, identityVersion);
        if (update == null) {
            return;
        }

        if (!includePatches) {
            try {
                IoUtils.copyFile(update, targetFile);
                return;
            } catch (IOException e) {
                throw new PatchingException("Failed to copy " + update.getAbsolutePath() + " to "
                        + targetFile.getAbsolutePath());
            }
        }

        final Patch updateMetaData = PatchUtil.readMetaData(update);
        final PatchBundleBuilder bundleBuilder = PatchBundleBuilder.create();
        bundleBuilder.add(update);
        bundlePatches(updateMetaData.getIdentity().getName(), updateMetaData.getIdentity().getVersion(), bundleBuilder);
        bundleBuilder.build(targetFile, true);
    }

    public void getUpdateToLatest(String identityName, String identityVersion, boolean includePatches, File targetFile)
            throws PatchingException {
        getUpdate(identityName, identityVersion, null, includePatches, targetFile);
    }

    public void getUpdate(String identityName, String identityVersion, String toVersion, boolean includePatches, File targetFile)
            throws PatchingException {
        File update = getUpdateOnly(identityName, identityVersion);
        if (update == null) {
            return;
        }
        final PatchBundleBuilder bundleBuilder = PatchBundleBuilder.create();
        boolean reachedTargetVersion = false;
        while (update != null && !reachedTargetVersion) {
            bundleBuilder.add(update);
            final Patch updateMetaData = PatchUtil.readMetaData(update);
            identityName = updateMetaData.getIdentity().getName();
            identityVersion = updateMetaData.getIdentity().forType(PatchType.CUMULATIVE, Identity.IdentityUpgrade.class).getResultingVersion();
            if (identityVersion.equals(toVersion)) {
                reachedTargetVersion = true;
            } else {
                update = getUpdateOnly(identityName, identityVersion);
            }
        }
        if (!reachedTargetVersion && toVersion != null) {
            throw new PatchingException("Failed to locate update path to " + toVersion + ", latest available is "
                    + identityVersion);
        }
        if (includePatches) {
            bundlePatches(identityName, identityVersion, bundleBuilder);
        }
        bundleBuilder.build(targetFile, true);
    }

    /**
     * @param identityName
     * @param identityVersion
     * @param bundleBuilder
     * @throws PatchingException
     */
    private void bundlePatches(String identityName, String identityVersion, final PatchBundleBuilder bundleBuilder)
            throws PatchingException {
        final File f = new File(getIdentityDir(identityName, identityVersion), PATCHES);
        if (!f.exists()) {
            return;
        }

        final File[] patchDirs = f.listFiles();
        for(File patchDir : patchDirs) {
            final File patchFile = getTmpPatchFile(identityName, identityVersion, patchDir.getName());
            getPatch(identityName, identityVersion, patchDir.getName(), false, patchFile);
            bundleBuilder.add(patchFile);
        }
    }

    private File getUpdateOnly(String identityName, String identityVersion) throws PatchingException {

        final File f = new File(getIdentityDir(identityName, identityVersion), UPDATES);
        if (!f.exists()) {
            return null;
        }
        final String[] updates = f.list();
        if (updates.length == 0) {
            return null;
        }

        if (updates.length > 1) {
            throw new PatchingException("There is more than one update for " + identityName + "-" + identityVersion + ": "
                    + Arrays.asList(updates));
        }
        final String update = updates[0];
        final File targetFile = getTmpPatchFile(identityName, identityVersion, update);
        getPatch(identityName, identityVersion, update, true, targetFile);
        return targetFile;
    }

    public void getPatch(String identityName, String identityVersion, String patchId, boolean update, File targetFile) throws PatchingException {

        final File patchDir = getPatchDir(identityName, identityVersion, patchId, update);
        if (!patchDir.exists()) {
            throw new PatchingException("Failed to locate patch " + patchId + " for identity "
                    + getIdentityDirName(identityName, identityVersion));
        }

        final File elementsFile = new File(patchDir, ELEMENTS_TXT);
        if (!elementsFile.exists()) {
            throw new PatchingException(elementsFile + " does not exist");
        }
        final Properties layers = new Properties();
        FileReader propsReader = null;
        try {
            layers.load(new FileReader(elementsFile));
        } catch (IOException e) {
            throw new PatchingException("Failed to load " + elementsFile.getAbsolutePath(), e);
        } finally {
            IoUtils.safeClose(propsReader);
        }


        ZipOutputStream zipOut = null;
        try {
            zipOut = new ZipOutputStream(new FileOutputStream(targetFile));

            for(File f : patchDir.listFiles()) {
                final String name = f.getName();
                if(name.equals(ELEMENTS_TXT) ||
                        name.equals(MISC_FILES_XML) ||
                        name.equals(UPDATE_TXT)) {
                    continue;
                }
                if(f.isDirectory()) {
                    addDirectoryToZip(f, name, zipOut);
                } else {
                    addFileToZip(f, null, zipOut);
                }
            }

            final Enumeration<?> layerNames = layers.propertyNames();
            while (layerNames.hasMoreElements()) {
                final String layer = (String) layerNames.nextElement();
                final String elementId = layers.getProperty(layer);

                final File elementDir = getFile(root, LAYERS, layer, update ? UPDATES : PATCHES, elementId, elementId);
                if(!elementDir.exists()) {
                    throw new PatchingException("Directory is missing for element " + elementId);
                }
                addDirectoryToZip(elementDir, elementId, zipOut);
            }

            zipOut.putNextEntry(new ZipEntry(PatchXml.PATCH_XML));
            final byte[] patchXml = getPatchXml(identityName, identityVersion, patchId, update).getBytes();
            zipOut.write(patchXml, 0,  patchXml.length);
            zipOut.closeEntry();
        } catch (IOException e) {
            throw new PatchingException("Failed to write patch to " + targetFile.getAbsolutePath(), e);
        } finally {
            IoUtils.safeClose(zipOut);
        }
    }

    public String getPatchXml(String identityName, String identityVersion, String patchId, boolean update)
            throws PatchingException {

        final File patchDir = getPatchDir(identityName, identityVersion, patchId, update);
        if (!patchDir.exists()) {
            throw new PatchingException("Failed to locate patch " + patchId + " for identity "
                    + getIdentityDirName(identityName, identityVersion));
        }

        final File elementsFile = new File(patchDir, ELEMENTS_TXT);
        if (!elementsFile.exists()) {
            throw new PatchingException(elementsFile + " does not exist");
        }
        final Properties layers = new Properties();
        FileReader propsReader = null;
        try {
            layers.load(new FileReader(elementsFile));
        } catch (IOException e) {
            throw new PatchingException("Failed to load " + elementsFile.getAbsolutePath(), e);
        } finally {
            IoUtils.safeClose(propsReader);
        }

        final StringBuilder elements = new StringBuilder();
        final Enumeration<?> layerNames = layers.propertyNames();
        while (layerNames.hasMoreElements()) {
            final String layer = (String) layerNames.nextElement();
            final String elementId = layers.getProperty(layer);

            final File elementXml = getFile(root, LAYERS, layer, update ? UPDATES : PATCHES, elementId, ELEMENT_XML);
            if (!elementXml.exists()) {
                throw new PatchingException("Failed to locate " + elementXml.getAbsolutePath());
            }

            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(elementXml));
                String line = reader.readLine();
                while (line != null) {
                    elements.append(line).append(LN);
                    line = reader.readLine();
                }
            } catch (IOException e) {
                throw new PatchingException("Failed to read " + elementXml.getAbsolutePath(), e);
            } finally {
                IoUtils.safeClose(reader);
            }
        }

        String miscFiles = null;
        final File miscXmlFile = getFile(patchDir, MISC_FILES_XML);
        if (miscXmlFile.exists()) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(miscXmlFile));
                String line = reader.readLine();
                final StringBuilder buf = new StringBuilder();
                while (line != null) {
                    buf.append(line).append(LN);
                    line = reader.readLine();
                }
                miscFiles = buf.toString();
            } catch (IOException e) {
                throw new PatchingException("Failed to read " + miscXmlFile.getAbsolutePath(), e);
            } finally {
                IoUtils.safeClose(reader);
            }
        }

        final Map<String, String> vars = new HashMap<String, String>();
        vars.put("patch-id", patchId);
        if (update) {
             vars.put("patch-type", "upgrade");

             final File updateTxt = getFile(patchDir, UPDATE_TXT);
             if(!updateTxt.exists()) {
                 throw new PatchingException(UPDATE_TXT + " is missing for " + patchId);
             }
             vars.put("to-version", "to-version=\"" + readList(updateTxt).get(0) + "\"");
        } else {
            vars.put("patch-type", "no-upgrade");
            vars.put("to-version", null);
        }
        vars.put("identity-name", identityName);
        vars.put("identity-version", identityVersion);
        vars.put("elements", elements.toString());
        vars.put("misc-files", miscFiles);

        final String patchXmlTemplate = getPatchXmlTemplate();
        final StringBuilder xml = new StringBuilder();

        final BufferedReader reader = new BufferedReader(new StringReader(patchXmlTemplate));
        try {
            String line = reader.readLine();
            while (line != null) {
                int at = line.indexOf('@');
                if (at >= 0) {
                    final StringBuilder buf = new StringBuilder();
                    int from = 0;
                    while (at >= 0) {
                        buf.append(line.substring(from, at));

                        boolean replaced = false;
                        for (String name : vars.keySet()) {
                            if (line.startsWith(name, at + 1)) {
                                final String value = vars.get(name);
                                if (value != null) {
                                    buf.append(value);
                                }
                                from = at + name.length() + 1;
                                replaced = true;
                                break;
                            }
                        }
                        if (!replaced) {
                            from = at;
                        }
                        at = line.indexOf('@', at + 1);
                    }
                    if (from < line.length() - 1) {
                        buf.append(line.substring(from));
                    }
                    line = buf.toString();
                }
                xml.append(line).append(LN);
                line = reader.readLine();
            }
        } catch (IOException e) {
            throw new PatchingException("Failed to generate patch.xml", e);
        } finally {
            IoUtils.safeClose(reader);
        }

        return xml.toString();
    }

    private File addPatchDir(Patch patch, String patchXml) throws PatchingException {

        final File identityDir = new File(root, getIdentityDirName(patch));
        ensureDir(identityDir);

        final PatchType patchType = patch.getIdentity().getPatchType();
        File patchDir = new File(identityDir, (patchType == PatchType.ONE_OFF ? PATCHES : UPDATES));
        patchDir = new File(patchDir, patch.getPatchId());
        ensureDir(patchDir);

        BufferedWriter writer = null;

        if(patchType == PatchType.CUMULATIVE) {
            try {
                writer = new BufferedWriter(new FileWriter(new File(patchDir, UPDATE_TXT)));
                writer.write(patch.getIdentity().forType(PatchType.CUMULATIVE, IdentityUpgrade.class).getResultingVersion());
            } catch (IOException e) {
                throw new PatchingException("Failed to writer " + new File(patchDir, UPDATE_TXT), e);
            } finally {
                IoUtils.safeClose(writer);
            }
        }

        final Properties elements = new Properties();
        for (PatchElement e : patch.getElements()) {
            elements.setProperty(e.getProvider().getName(), e.getId());
        }

        writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(new File(patchDir, ELEMENTS_TXT)));
            elements.store(writer, null);
        } catch (IOException e) {
            throw new PatchingException("Failed to writer " + new File(patchDir, ELEMENTS_TXT), e);
        } finally {
            IoUtils.safeClose(writer);
        }

        final int miscInd = patchXml.indexOf("<misc-files>");
        if (miscInd > 0) {
            int lineInd = patchXml.lastIndexOf(LN, miscInd);
            if (lineInd < 0) {
                lineInd = miscInd;
            } else {
                lineInd += LN.length();
            }
            int end = patchXml.indexOf("</misc-files>", miscInd);
            if (end < 0) {
                throw new PatchingException("Failed to locate </misc-files> in patch.xml");
            }
            end += "</misc-files>".length();
            final String miscFiles = patchXml.substring(miscInd, end);
            try {
                writer = new BufferedWriter(new FileWriter(new File(patchDir, MISC_FILES_XML)));
                writer.write(miscFiles);
            } catch (IOException e) {
                throw new PatchingException("Failed to writer " + new File(patchDir, MISC_FILES_XML), e);
            } finally {
                IoUtils.safeClose(writer);
            }
        }
        return patchDir;
    }

    private File addElementDir(Patch patch, PatchElement element, String patchXml) throws PatchingException {

        final File layerDir = getFile(root, LAYERS, element.getProvider().getName());
        ensureDir(layerDir);

        final PatchType patchType = element.getProvider().getPatchType();
        File elementDir = new File(layerDir, (patchType == PatchType.ONE_OFF ? PATCHES : UPDATES));
        elementDir = new File(elementDir, element.getId());
        ensureDir(elementDir);

        try {
            final String elementXml = getElementXml(patchXml, element.getId());
            IoUtils.copyStreamAndClose(new ByteArrayInputStream(elementXml.getBytes()),
                    new FileOutputStream(new File(elementDir, ELEMENT_XML)));
        } catch (IOException ex) {
            throw new PatchingException("Failed to write " + new File(elementDir, ELEMENT_XML).getAbsolutePath(), ex);
        }

        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(new File(elementDir, TARGET_IDENTITY_TXT)));
            writer.write(patch.getIdentity().getName());
            writer.newLine();
            writer.write(patch.getIdentity().getVersion());
        } catch (IOException e) {
            throw new PatchingException("Failed to write to " + new File(elementDir, TARGET_IDENTITY_TXT).getAbsolutePath(), e);
        } finally {
            IoUtils.safeClose(writer);
        }

        return elementDir;
    }

    /**
     * @param patchXml
     * @param eId
     * @return
     */
    private String getElementXml(final String patchXml, String eId) {
        int start = patchXml.indexOf(eId);
        start = patchXml.lastIndexOf("<element", start);
//        int lineInd = patchXml.lastIndexOf(LN, start);
//        if (lineInd > 0) {
//            start = lineInd + LN.length();
//        }
        int end = patchXml.indexOf("</element>", start);
        if (end > 0) {
            end += "</element>".length();
        }
        return patchXml.substring(start, end);
    }

    /**
     * @param patchXml
     * @throws PatchingException
     */
    private Patch parsePatchXml(final String patchXml) throws PatchingException {
        try {
            return PatchXml.parse(new ByteArrayInputStream(patchXml.getBytes())).resolvePatch(null, null);
        } catch (XMLStreamException e) {
            throw new PatchingException("Failed to parse " + PatchXml.PATCH_XML, e);
        }
    }

    /**
     * @param zipFile
     * @return
     * @throws IOException
     */
    private String getPatchXml(ZipFile zipFile) throws PatchingException, IOException {
        ZipEntry patchXmlEntry = zipFile.getEntry(PatchXml.PATCH_XML);
        if (patchXmlEntry == null) {
            throw new PatchingException("Patch file is missing " + PatchXml.PATCH_XML + ": " + zipFile.getName());
        }
        InputStream is = null;
        try {
            is = zipFile.getInputStream(patchXmlEntry);
            final BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            String line = reader.readLine();
            final StringBuilder buf = new StringBuilder();
            while (line != null) {
                buf.append(line).append(LN);
                line = reader.readLine();
            }
            return buf.toString();
        } finally {
            IoUtils.safeClose(is);
        }
    }

    /**
     * @param targetDir
     * @throws PatchingException
     */
    private void ensureDir(File targetDir) throws PatchingException {
        if (!targetDir.exists()) {
            if (!targetDir.mkdirs()) {
                throw new PatchingException("Failed to create target directory " + targetDir.getAbsolutePath());
            }
        } else if (!targetDir.isDirectory()) {
            throw new PatchingException("Target path is not a directory " + targetDir.getAbsolutePath());
        }
    }

    private String getPatchXmlTemplate() throws PatchingException {
        final InputStream is = getClass().getResourceAsStream(TEMPLATE_PATCH_XML);
        if (is == null) {
            throw new PatchingException("Failed to locate " + TEMPLATE_PATCH_XML);
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(is));
            String line = reader.readLine();
            final StringBuilder buf = new StringBuilder();
            if (line != null) {
                buf.append(line);
                line = reader.readLine();
            }
            while (line != null) {
                buf.append(LN).append(line);
                line = reader.readLine();
            }
            return buf.toString();
        } catch (IOException e) {
            throw new PatchingException("Failed to read " + TEMPLATE_PATCH_XML);
        } finally {
            IoUtils.safeClose(reader);
        }
    }

    private List<String> readList(File f) throws PatchingException {

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(f));
            String line = reader.readLine();
            final List<String> list = new ArrayList<String>();
            while (line != null) {
                list.add(line);
                line = reader.readLine();
            }
            return list;
        } catch (IOException e) {
            throw new PatchingException("Failed to read " + f.getAbsolutePath(), e);
        } finally {
            IoUtils.safeClose(reader);
        }
    }

    /**
     * @param targetFile
     * @param file
     * @throws PatchingException
     */
    private void appendFile(final File file, final String line) throws PatchingException {
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(file, true));
            writer.append(line);
            writer.newLine();
        } catch (IOException e) {
            throw new PatchingException("Failed to write to " + file.getAbsolutePath(), e);
        } finally {
            IoUtils.safeClose(writer);
        }
    }

    private File getPatchDir(String name, String version, String patchId, boolean update) {
        File dir = new File(root, getIdentityDirName(name, version));
        dir = new File(dir, update ? UPDATES : PATCHES);
        dir = new File(dir, patchId);
        return dir;
    }

    private static String getIdentityDirName(Patch patch) {
        assert patch != null : "patch is null";
        return getIdentityDirName(patch.getIdentity().getName(), patch.getIdentity().getVersion());
    }

    private static String getIdentityDirName(String name, String version) {
        return name + '-' + version;
    }

    private File getTmpPatchFile(String identityName, String identityVersion, String patchId) {
        return getFile(TMP_DIR, getIdentityDirName(identityName, identityVersion) + "-" + patchId + ".zip");
    }

    private File getFile(File parent, String... names) {
        if (names.length == 0) {
            return parent;
        }
        for (String name : names) {
            parent = new File(parent, name);
        }
        return parent;
    }

    private static void addDirectoryToZip(File dir, String dirName, ZipOutputStream zos) throws IOException {

        final ZipEntry dirEntry = new ZipEntry(dirName + "/");
        zos.putNextEntry(dirEntry);
        zos.closeEntry();

        File[] children = dir.listFiles();
        if (children != null) {
            for (File file : children) {
                if (file.isDirectory()) {
                    addDirectoryToZip(file, dirName + "/" + file.getName(), zos);
                } else {
                    addFileToZip(file, dirName, zos);
                }
            }
        }
    }

    private static void addFileToZip(File file, String parent, ZipOutputStream zos) throws IOException {
        final FileInputStream is = new FileInputStream(file);
        try {
            final String entryName = parent == null ? file.getName() : parent + "/" + file.getName();
            zos.putNextEntry(new ZipEntry(entryName));

            final BufferedInputStream bis = new BufferedInputStream(is);
            try {
                copyStream(bis, zos);
            } finally {
                safeClose(bis);
            }

            zos.closeEntry();
        } finally {
            safeClose(is);
        }
    }
}
