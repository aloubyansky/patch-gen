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
import java.util.Comparator;
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
import org.jboss.as.patching.util.IdentityInfo.AddOnInfo;
import org.jboss.as.patching.util.IdentityInfo.Builder;

/**
 * Patch repository.
 *
 * <pre>
 * <code>
 * ROOT
 * |-- layers
 * |   |-- layer1
 * |   |...
 * |   `-- layerN
 * |       |-- patches
 * |       |   |-- layer-version-1
 * |       |   |...
 * |       |   `-- layer-version-N
 * |       |       |-- patchElementId
 * |       |       |...
 * |       |       `-- patchElementId
 * |       |           |-- element.xml
 * |       |           |-- target-version.txt
 * |       |           `-- FS-tree content
 * |       |
 * |       `-- updates
 * |           |-- layer-version-1
 * |           |...
 * |           `-- layer-version-N
 * |               |-- allowed-identities.txt
 * |               |-- patchElementId
 * |               |...
 * |               `-- patchElementId
 * |                   |-- element.xml
 * |                   |-- target-version.txt
 * |                   `-- FS-tree content
 * |
 * |-- addons (same as layers)
 * |
 * |-- identity-name-version-1
 * |...
 * `-- identity-name-version-N
 *     |-- patches
 *     |   |-- patchId
 *     |   |...
 *     |   `-- patchId
 *     |       |-- elements.txt
 *     |       `-- misc content
 *     `-- updates
 *         |-- updateId
 *         |...
 *         `-- updateId
 *             |-- elements.txt
 *             |-- updated-version.txt
 *             `-- misc content
 * </code>
 * </pre>
 *
 * @author Alexey Loubyansky
 */
public class PatchRepository {

    private static final String ADDONS = "addons";
    private static final String ALLOWED_IDENTITIES_TXT = "allowed-identities.txt";
    private static final String BASE = "base";
    private static final String ELEMENT_XML = "element.xml";
    private static final String ELEMENTS_TXT = "elements.txt";
    private static final String LAYERS = "layers";
    private static final String MISC_FILES_XML = "misc-files.xml";
    private static final String PATCHES = "patches";
    private static final String TARGET_VERSION_TXT = "target-version.txt";
    private static final String TEMPLATE_PATCH_XML = "template-patch.xml";
    private static final String UPDATED_VERSION_TXT = "updated-version.txt";
    private static final String UPDATES = "updates";
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

            final Map<String, PatchElement> elements = new HashMap<String, PatchElement>();
            boolean addonsOnly = patch.getElements().size() > 0;
            for (PatchElement e : patch.getElements()) {
                elements.put(e.getId(), e);
                if(addonsOnly && !e.getProvider().isAddOn()) {
                    addonsOnly = false;
                }
            }

            final File patchDir = addonsOnly ? null : addPatchDir(patch, patchXml);

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
                        } else if(patchDir != null) {
                            if (name.equals(patch.getPatchId())) {
                                currentDir = patchDir;
                            } else {
                                // non-patch content
                                currentDir = patchDir;
                            }
                        } else {
                            currentDir = null;
                            rootDirEntry = null;
                        }
                    } else {
                        rootDirEntry = null;
                        currentDir = null;
                        // TODO the misc stuff here is left behind in case of add-ons, which has to be addressed at some point
                        if (patchDir != null && !entry.getName().equals(PatchXml.PATCH_XML)) {
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

    public List<Patch> getPatchesInfo(IdentityInfo identity) throws PatchingException {

        final File patchesDir = new File(getIdentityDir(identity.getName(), identity.getVersion()), PATCHES);
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
            patches.add(parsePatchXml(getPatchXml(identity, child.getName(), null)));
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

    public Patch getUpdateInfo(String identityName, String identityVersion) throws PatchingException {
        return getUpdateInfo(IdentityInfo.Builder.create(identityName, identityVersion).build());
    }

    public Patch getUpdateInfo(IdentityInfo identity) throws PatchingException {

        final File updateDir = new File(getIdentityDir(identity.getName(), identity.getVersion()), UPDATES);
        if (!updateDir.exists()) {
            return null;
        }
        if (updateDir.isFile()) {
            throw new PatchingException(updateDir.getAbsolutePath() + " is not a directory");
        }

        final File[] children = updateDir.listFiles();
        final List<Patch> updates = new ArrayList<Patch>(children.length);
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            final String patchXml = getPatchXml(identity, child.getName(), getIdentityUpdatedVersion(identity, child.getName()));
            updates.add(parsePatchXml(patchXml));
        }
        if(updates.size() > 1) {
            throw new PatchingException("There is more than one update for " + getIdentityDirName(identity.getName(), identity.getVersion()));
        }
        return updates.get(0);
    }

    public File bundlePatches(IdentityInfo identity, File targetDir) throws PatchingException {

        if (targetDir == null) {
            throw new IllegalArgumentException("targetDir is null");
        }

        final PatchBundleBuilder bundleBuilder = PatchBundleBuilder.create();
        bundlePatches(identity, bundleBuilder);

        ensureDir(targetDir);
        final File targetFile = new File(targetDir, getIdentityDirName(identity.getName(), identity.getVersion()) + "-" + PATCHES + ".zip");
        bundleBuilder.build(targetFile, true);
        return targetFile;
    }

    public void getUpdateToNext(IdentityInfo identity, boolean includePatches, File targetFile)
            throws PatchingException {

        final File update = getUpdateOnly(identity);
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
        // TODO take into account add-on versions!
        identity = IdentityInfo.Builder.create(updateMetaData.getIdentity().getName(), updateMetaData.getIdentity().getVersion()).build();
        final PatchBundleBuilder bundleBuilder = PatchBundleBuilder.create();
        bundleBuilder.add(update);
        bundlePatches(identity, bundleBuilder);
        bundleBuilder.build(targetFile, true);
    }

    public void getUpdateToLatest(IdentityInfo identity, boolean includePatches, File targetFile)
            throws PatchingException {
        getUpdate(identity, null, includePatches, targetFile);
    }

    public void getUpdate(IdentityInfo identity, String toVersion, boolean includePatches, File targetFile)
            throws PatchingException {
        File update = getUpdateOnly(identity);
        if (update == null) {
            return;
        }
        final PatchBundleBuilder bundleBuilder = PatchBundleBuilder.create();
        boolean reachedTargetVersion = false;
        while (update != null && !reachedTargetVersion) {
            bundleBuilder.add(update);
            final Patch updateMetaData = PatchUtil.readMetaData(update);
            // TODO take info account add-on versions!
            identity = IdentityInfo.Builder.create(updateMetaData.getIdentity().getName(),
                    updateMetaData.getIdentity().forType(PatchType.CUMULATIVE, Identity.IdentityUpgrade.class).getResultingVersion()).build();
            if (identity.getVersion().equals(toVersion)) {
                reachedTargetVersion = true;
            } else {
                update = getUpdateOnly(identity);
            }
        }
        if (!reachedTargetVersion && toVersion != null) {
            throw new PatchingException("Failed to locate update path to " + toVersion + ", latest available is "
                    + identity.getVersion());
        }
        if (includePatches) {
            bundlePatches(identity, bundleBuilder);
        }
        bundleBuilder.build(targetFile, true);
    }

    public boolean hasAddonPatches(IdentityInfo identity) throws PatchingException {
        for(AddOnInfo addon : listAddonNames(identity)) {
            final File patchesDir = getFile(root, ADDONS, addon.getName(), PATCHES, addon.getVersion());
            if(!patchesDir.exists()) {
                continue;
            }
            if(!patchesDir.isDirectory()) {
                throw new PatchingException(patchesDir.getAbsolutePath() + " is not a directory");
            }
            for (File patchDir : patchesDir.listFiles()) {
                final File targets = getFile(patchDir, TARGET_VERSION_TXT);
                if (!targets.exists()) {
                    continue;
                }
                if (readList(targets).contains(addon.getVersion())) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasAddonUpdates(IdentityInfo identity) throws PatchingException {
        final String identityStr = getIdentityDirName(identity.getName(), identity.getVersion());
        for(AddOnInfo addon : listAddonNames(identity)) {
            final File updatesDir = getFile(root, ADDONS, addon.getName(), UPDATES);
            final File currentVersionDir = getFile(updatesDir, addon.getVersion());
            if(!currentVersionDir.exists()) {
                continue;
            }
            if(!currentVersionDir.isDirectory()) {
                throw new PatchingException(currentVersionDir.getAbsolutePath() + " is not a directory");
            }
            for (String updateId : currentVersionDir.list()) {
                if(updateId.equals(ALLOWED_IDENTITIES_TXT)) {
                    continue;
                }
                final File targets = getFile(updatesDir, updateId, ALLOWED_IDENTITIES_TXT);
                if (!targets.exists()) {
                    continue;
                }
                if (readList(targets).contains(identityStr)) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<Patch> getAddonPatchesInfo(IdentityInfo identity) throws PatchingException {

        final List<Patch> patches = new ArrayList<Patch>();
        for(AddOnInfo addon : listAddonNames(identity)) {
            final File patchesDir = getFile(root, ADDONS, addon.getName(), PATCHES, addon.getVersion());
            if(!patchesDir.exists()) {
                continue;
            }
            if(!patchesDir.isDirectory()) {
                throw new PatchingException(patchesDir.getAbsolutePath() + " is not a directory");
            }
            for (File patchDir : patchesDir.listFiles()) {
                final File targets = getFile(patchDir, TARGET_VERSION_TXT);
                if (!targets.exists()) {
                    continue;
                }
                if (readList(targets).contains(addon.getVersion())) {
                    final String patchXml = generatePatchXml(identity.getName(), identity.getVersion(), null,
                            addon.getVersion(), readFile(getFile(patchDir, ELEMENT_XML)), null);
                    patches.add(parsePatchXml(patchXml));
                }
            }
        }
        return patches;
    }

    public void acceptAddonForIdentity(String addonName, String updateId, String identityName, String identityVersion, boolean notRegisteredToo)
            throws PatchingException {

        /*
         * The problem with identity updates is that the current impl will rollback all the one-offs applied before applying the update.
         * This will rollback all the one-offs for add-ons too even if the current add-on versions are allowed for the next identity version.
         * So, this means the currently applied one-off add-on patches will have to be re-applied with the identity update.
         * And allowing an add-on update for an identity version will also make all the one-off patches and update for this add-on also
         * applicable to this identity version.
         */

        if(addonName == null) {
            throw new IllegalArgumentException("addonName is null");
        }
        if(updateId == null) {
            throw new IllegalArgumentException("updateId is null");
        }
        if(identityName == null) {
            throw new IllegalArgumentException("identityName is null");
        }
        if(identityVersion == null) {
            throw new IllegalArgumentException("identityVersion is null");
        }

        final File addonDir = getAddonUpdateDir(addonName, updateId);
        if(!addonDir.exists()) {
            if(notRegisteredToo) {
                if(!addonDir.mkdirs()) {
                    throw new PatchingException("Failed to create directory for add-on " + addonName);
                }
            } else {
                throw new PatchingException("Update " + updateId + " is not registered for add-on " + addonName);
            }
        }

        final File targetIdentity = new File(addonDir, ALLOWED_IDENTITIES_TXT);
        appendFile(targetIdentity, getIdentityDirName(identityName, identityVersion));
    }

    private void bundlePatches(IdentityInfo identity, final PatchBundleBuilder bundleBuilder)
            throws PatchingException {
        final File f = new File(getIdentityDir(identity.getName(), identity.getVersion()), PATCHES);
        if (!f.exists()) {
            return;
        }

        final File[] patchDirs = f.listFiles();
        for(File patchDir : patchDirs) {
            final File patchFile = getTmpPatchFile(identity.getName(), identity.getVersion(), patchDir.getName());
            getPatch(identity, patchDir.getName(), false, patchFile);
            bundleBuilder.add(patchFile);
        }
    }

    private File getUpdateOnly(IdentityInfo identity) throws PatchingException {

        final File f = new File(getIdentityDir(identity.getName(), identity.getVersion()), UPDATES);
        if (!f.exists()) {
            return null;
        }
        final String[] updates = f.list();
        if (updates.length == 0) {
            return null;
        }

        if (updates.length > 1) {
            throw new PatchingException("There is more than one update for " + identity.getName() + "-" + identity.getVersion() + ": "
                    + Arrays.asList(updates));
        }
        final String update = updates[0];
        final File targetFile = getTmpPatchFile(identity.getName(), identity.getVersion(), update);
        getPatch(identity, update, true, targetFile);
        return targetFile;
    }

    private void getPatch(IdentityInfo identity, String patchId, boolean update, File targetFile) throws PatchingException {

        final File patchDir = getPatchDir(identity.getName(), identity.getVersion(), patchId, update);
        if (!patchDir.exists()) {
            throw new PatchingException("Failed to locate patch " + patchId + " for identity "
                    + getIdentityDirName(identity.getName(), identity.getVersion()));
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
                        name.equals(UPDATED_VERSION_TXT)) {
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
                String elementId = layers.getProperty(layer);
                final int atIndex = elementId.indexOf('@');
                if(atIndex < 0) {
                    throw new PatchingException("Provider version is missing in '" + elementId + "' for provider " + layer);
                }
                final String providerVersion = elementId.substring(atIndex + 1);
                if(providerVersion.isEmpty()) {
                    throw new PatchingException("Provider version is missing in '" + elementId + "' for provider " + layer);
                }
                elementId = elementId.substring(0, atIndex);

                final File elementDir = getFile(root, LAYERS, layer, update ? UPDATES : PATCHES, providerVersion, elementId, elementId);
                if(!elementDir.exists()) {
                    throw new PatchingException("Directory is missing for element " + elementId);
                }
                addDirectoryToZip(elementDir, elementId, zipOut);
            }

            String identityUpdatedVersion = null;
            if(update) {
                identityUpdatedVersion = getIdentityUpdatedVersion(identity, patchId);
                final List<AddOnInfo> addOns = identity.getAddOns();
                if (!addOns.isEmpty()) {
                    for (AddOnInfo addOn : addOns) {
                        File addOnUpdateDir = getAddOnUpdateDir(identity, identityUpdatedVersion, addOn);
                        if(addOnUpdateDir == null) {
                            continue;
                        }
                        addOnUpdateDir = new File(addOnUpdateDir, addOnUpdateDir.getName());
                        if(!addOnUpdateDir.exists()) {
                            throw new PatchingException("Content directory is missing for add-on " + addOnUpdateDir.getAbsolutePath());
                        }
                        addDirectoryToZip(addOnUpdateDir, addOnUpdateDir.getName(), zipOut);
                    }
                }
            }

            zipOut.putNextEntry(new ZipEntry(PatchXml.PATCH_XML));
            final byte[] patchXml = getPatchXml(identity, patchId, identityUpdatedVersion).getBytes();
            zipOut.write(patchXml, 0,  patchXml.length);
            zipOut.closeEntry();
        } catch (IOException e) {
            throw new PatchingException("Failed to write patch to " + targetFile.getAbsolutePath(), e);
        } finally {
            IoUtils.safeClose(zipOut);
        }
    }

    private String getPatchXml(IdentityInfo identity, String patchId, String identityUpdatedVersion)
            throws PatchingException {

        final File patchDir = getPatchDir(identity.getName(), identity.getVersion(), patchId, identityUpdatedVersion != null);
        if (!patchDir.exists()) {
            throw new PatchingException("Failed to locate patch " + patchId + " for identity "
                    + getIdentityDirName(identity.getName(), identity.getVersion()));
        }

        String elementsXml = getLayerElementsXml(patchDir, identityUpdatedVersion != null);

        final List<AddOnInfo> addOns = identity.getAddOns();
        if(!addOns.isEmpty()) {
            StringBuilder addonElements = null;
            for(AddOnInfo addOn : addOns) {
                // Only if it's an update, one-offs should be applied as separate patches
                if(identityUpdatedVersion != null) {
                    final String elementXml = readFile(new File(getAddOnUpdateDir(identity, identityUpdatedVersion, addOn), ELEMENT_XML));
                    if(elementXml != null) {
                        if(addonElements == null) {
                            addonElements = new StringBuilder();
                            addonElements.append(elementsXml).append(LN);
                        }
                        addonElements.append(elementXml);
                    }
                }
            }
            if(addonElements.length() > 0) {
                elementsXml = addonElements.toString();
            }
        }

        return generatePatchXml(identity.getName(), identity.getVersion(), identityUpdatedVersion, patchId, elementsXml, getPatchMiscXml(patchDir));
    }

    private String generatePatchXml(String identityName, String identityVersion, String toVersion, String patchId, String elements, String misc)
            throws PatchingException {

        final Map<String, String> vars = new HashMap<String, String>();
        vars.put("patch-id", patchId);
        if (toVersion != null) {
            vars.put("patch-type", "upgrade");
            vars.put("to-version", "to-version=\"" + toVersion + "\"");
        } else {
            vars.put("patch-type", "no-upgrade");
            vars.put("to-version", null);
        }
        vars.put("identity-name", identityName);
        vars.put("identity-version", identityVersion);
        vars.put("elements", elements);
        vars.put("misc-files", misc);

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

    private String getPatchMiscXml(final File patchDir) throws PatchingException {
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
        return miscFiles;
    }

    private File getAddOnUpdateDir(IdentityInfo identity, String updatedIdentityVersion, AddOnInfo addOn)
            throws PatchingException {
        final List<File> updateDirs = getUpdateDirs(identity, updatedIdentityVersion, addOn);
        if(updateDirs.isEmpty()) {
            if(!isAddOnSupported(addOn.getName(), addOn.getVersion(), identity.getName(), identity.getVersion())) {
                throw new PatchingException("Failed to locate version of add-on " + addOn.getName() +
                        " supported in identity " + getIdentityDirName(identity.getName(), updatedIdentityVersion));
            }
            return null;
        } else {
            if(updateDirs.size() > 1) {
                // TODO another questionable ordering to determine the latest version
                Collections.sort(updateDirs, new Comparator<File>(){
                    @Override
                    public int compare(File o1, File o2) {
                        return o1.getName().compareTo(o2.getName());
                    }});
            }
            return updateDirs.get(updateDirs.size() - 1);
        }
    }

    private List<File> getUpdateDirs(IdentityInfo identity, String updatedIdentityVersion, AddOnInfo addOn) throws PatchingException {
        List<File> updateDirs = Collections.emptyList();
        final File updatesDir = getFile(root, ADDONS, addOn.getName(), UPDATES, addOn.getVersion());
        if (updatesDir.exists()) {
            for (File updateDir : updatesDir.listFiles()) {
                if (updateDir.isFile()) {
                    continue;
                }
                if (isAddOnSupported(addOn.getName(), updateDir.getName(), identity.getName(), updatedIdentityVersion)) {
                    switch (updateDirs.size()) {
                        case 0:
                            updateDirs = Collections.singletonList(updateDir);
                            break;
                        case 1:
                            updateDirs = new ArrayList<File>(updateDirs);
                        default:
                            updateDirs.add(updateDir);
                    }
                }
            }
        }
        return updateDirs;
    }

    private String getLayerElementsXml(File patchDir, boolean update) throws PatchingException {

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

        return getElementsXml(layers, false, update);
    }

    private String getElementsXml(final Properties providers, boolean addons, boolean update) throws PatchingException {
        final StringBuilder elements = new StringBuilder();
        final String providerType = addons ? ADDONS : LAYERS;
        final String patchType = update ? UPDATES : PATCHES;
        final Enumeration<?> providerNames = providers.propertyNames();
        while (providerNames.hasMoreElements()) {
            final String provider = (String) providerNames.nextElement();
            String elementId = providers.getProperty(provider);
            final int atIndex = elementId.indexOf('@');
            if(atIndex < 0) {
                throw new PatchingException("Provider version is missing in '" + elementId + "' for provider " + provider);
            }
            final String providerVersion = elementId.substring(atIndex + 1);
            if(providerVersion.isEmpty()) {
                throw new PatchingException("Provider version is missing in '" + elementId + "' for provider " + provider);
            }
            elementId = elementId.substring(0, atIndex);

            final String str = readFile(getFile(root, providerType, provider, patchType, providerVersion, elementId, ELEMENT_XML));
            if(str != null) {
                elements.append(str);
            }
        }
        return elements.toString();
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
            writeFile(patch.getIdentity().forType(PatchType.CUMULATIVE, IdentityUpgrade.class).getResultingVersion(), new File(patchDir, UPDATED_VERSION_TXT));
        }

        final Properties elements = new Properties();
        for (PatchElement e : patch.getElements()) {
            String providerVersion = getProviderVersion(patch.getIdentity().getName(), patch.getIdentity().getVersion(), e.getProvider().getName(), e.getProvider().isAddOn());
            elements.setProperty(e.getProvider().getName(), e.getId() + "@" + providerVersion);
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

        final File layerDir = getFile(root, element.getProvider().isAddOn() ? ADDONS : LAYERS, element.getProvider().getName());
        ensureDir(layerDir);

        final String providerVersion = getProviderVersion(patch.getIdentity().getName(), patch.getIdentity().getVersion(),
                element.getProvider().getName(), element.getProvider().isAddOn());

        final PatchType patchType = element.getProvider().getPatchType();
        File elementDir = getFile(layerDir, (patchType == PatchType.ONE_OFF ? PATCHES : UPDATES), providerVersion);
        elementDir = new File(elementDir, element.getId());
        ensureDir(elementDir);

        try {
            final String elementXml = getElementXml(patchXml, element.getId());
            IoUtils.copyStreamAndClose(new ByteArrayInputStream(elementXml.getBytes()),
                    new FileOutputStream(new File(elementDir, ELEMENT_XML)));
        } catch (IOException ex) {
            throw new PatchingException("Failed to write " + new File(elementDir, ELEMENT_XML).getAbsolutePath(), ex);
        }

        final String targetVersion = getProviderVersion(patch.getIdentity().getName(), patch.getIdentity().getVersion(),
                element.getProvider().getName(), element.getProvider().isAddOn());
        writeFile(targetVersion, new File(elementDir, TARGET_VERSION_TXT));

        if(patchType == PatchType.CUMULATIVE) {
            final String allowedVersion = patch.getIdentity().getPatchType() == PatchType.CUMULATIVE ?
                    patch.getIdentity().forType(PatchType.CUMULATIVE, IdentityUpgrade.class).getResultingVersion() :
                        patch.getIdentity().getVersion();
            acceptAddonForIdentity(element.getProvider().getName(), element.getId(), patch.getIdentity().getName(), allowedVersion, true);
        }

        return elementDir;
    }

    private String getElementXml(final String patchXml, String eId) {
        int start = patchXml.indexOf(eId);
        start = patchXml.lastIndexOf("<element", start);
        int lineInd = patchXml.lastIndexOf(LN, start);
        if (lineInd > 0) {
            start = lineInd + LN.length();
        }
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

    private String readFile(final File f) throws PatchingException {
        if(!f.exists()) {
            throw new PatchingException("File does not exist " + f.getAbsolutePath());
        }
        BufferedReader reader = null;
        final StringBuilder buf = new StringBuilder();
        try {
            reader = new BufferedReader(new FileReader(f));
            String line = reader.readLine();
            if (line != null) {
                buf.append(line);
                line = reader.readLine();
                while (line != null) {
                    buf.append(LN).append(line);
                    line = reader.readLine();
                }
            }
        } catch (IOException e) {
            throw new PatchingException("Failed to read " + f.getAbsolutePath(), e);
        } finally {
            IoUtils.safeClose(reader);
        }
        return buf.length() == 0 ? null : buf.toString();
    }

    private void writeFile(final String line, File file) throws PatchingException {
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(file));
            writer.write(line);
        } catch (IOException e) {
            throw new PatchingException("Failed to write to " + file.getAbsolutePath(), e);
        } finally {
            IoUtils.safeClose(writer);
        }
    }

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

    private File getAddonUpdateDir(String addonName, String updateId) {
        return getFile(root, ADDONS, addonName, UPDATES, updateId);
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

    private List<AddOnInfo> listAddonNames(IdentityInfo identity) throws PatchingException {
        List<AddOnInfo> addons = identity.getAddOns();
        if(addons.isEmpty()) {
            final File addonsDir = getFile(root, ADDONS);
            if(!addonsDir.exists()) {
                return Collections.emptyList();
            }

            final Builder builder = IdentityInfo.Builder.create(identity.getName(), identity.getVersion());
            final String identityStr = identity.getName() + "-" + identity.getVersion();
            for(File addonDir : addonsDir.listFiles()) {
                final File updatesDir = new File(addonDir, UPDATES);
                if(!updatesDir.exists()) {
                    builder.addAddOn(addonDir.getName(), BASE);
                    continue;
                }
                if(!updatesDir.isDirectory()) {
                    throw new PatchingException(updatesDir.getAbsolutePath() + " is not a directory");
                }
                for(File versionDir : updatesDir.listFiles()) {
                    final File targets = getFile(versionDir, ALLOWED_IDENTITIES_TXT);
                    if (!targets.exists()) {
                        throw new PatchingException("Update is missing " + targets.getAbsolutePath());
                    }
                    if (readList(targets).contains(identityStr)) {
                        builder.addAddOn(addonDir.getName(), versionDir.getName());
                    }
                }
            }
            addons = builder.build().getAddOns();
        }
        return addons;
    }

    private boolean isAddOnSupported(String addOnName, String addOnVersion, String identityName, String identityVersion) throws PatchingException {
        File f = getFile(root, ADDONS, addOnName, UPDATES, addOnVersion);
        if(!f.exists()) {
            return false;
        }
        f = new File(f, ALLOWED_IDENTITIES_TXT);
        if(!f.exists()) {
            throw new PatchingException("Add-on update is missing " + f.getAbsolutePath());
        }
        return readList(f).contains(getIdentityDirName(identityName, identityVersion));
    }

    private String getProviderVersion(String identityName, String identityVersion, String providerName, boolean addon) throws PatchingException {

        final File versionsDir = getFile(root, addon ? ADDONS : LAYERS, providerName, UPDATES);
        if(!versionsDir.exists()) {
            return BASE;
        }

        final String identity = getIdentityDirName(identityName, identityVersion);
        List<String> elementIds = Collections.emptyList();
        for(File versionDir : versionsDir.listFiles()) {

            final File[] updates = versionDir.listFiles();
            if(updates.length == 0) {
                continue;
            }
            if(updates.length > 1) {
                throw new PatchingException(versionDir.getAbsolutePath() + " contains more than one update");
            }

            final File identitiesTxt = getFile(updates[0], ALLOWED_IDENTITIES_TXT);
            if (identitiesTxt.exists()) {
                final List<String> identities = readList(identitiesTxt);
                if (identities.contains(identity)) {
                    switch (elementIds.size()) {
                        case 0:
                            elementIds = Collections.singletonList(updates[0].getName());
                            break;
                        case 1:
                            elementIds = new ArrayList<String>(elementIds);
                        default:
                            elementIds.add(updates[0].getName());
                    }
                }
            }
        }

        if(elementIds.isEmpty()) {
            return BASE; // this is a bad and practically wrong assumption
        }
        if(elementIds.size() > 1) {
            Collections.sort(elementIds); // this is a silly way of determining the latest update version
        }
        return elementIds.get(elementIds.size() - 1);
    }

    private String getIdentityUpdatedVersion(IdentityInfo identity, String patchId) throws PatchingException {
        final File patchDir = getPatchDir(identity.getName(), identity.getVersion(), patchId, true);
        if (!patchDir.exists()) {
            throw new PatchingException("Failed to locate patch " + patchId + " for identity "
                    + getIdentityDirName(identity.getName(), identity.getVersion()));
        }
        return readFile(getFile(patchDir, UPDATED_VERSION_TXT));
    }
}
