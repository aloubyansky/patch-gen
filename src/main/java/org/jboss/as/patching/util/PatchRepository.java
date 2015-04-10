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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jboss.as.patching.IoUtils;
import org.jboss.as.patching.PatchingException;
import org.jboss.as.patching.logging.PatchLogger;
import org.jboss.as.patching.metadata.Patch;
import org.jboss.as.patching.metadata.Patch.PatchType;

/**
 * Patch repository.
 *
 * Every patch file added to the repository is copied to ROOT/patches directory.
 *
 * When a new patch is added, a new directory ROOT/identity-name-version corresponding to the target identity of the patch is
 * created (unless one already exists). If the patch is a one-off patch, the name of the patch file is added to
 * ROOT/identity-name-version/patches.txt. If the patch is a cumulative patch, the name of the patch file is added to
 * ROOT/identity-name-version/update.txt.
 *
 * <pre>
 * <code>
 * ROOT
 * |-- patches
 * |   |-- patchXXX.zip
 * |   |...
 * |   `-- patchYYY.zip
 * |
 * |-- identity-name-x.x.x
 * |   |-- patches.txt
 * |   `-- update.txt
 * |...
 * `-- identity-name-y.y.y
 *     |-- patches.txt
 *     `-- update.txt
 * </code>
 * </pre>
 *
 * @author Alexey Loubyansky
 */
public class PatchRepository {

    public static final String PATCHES = "patches";
    public static final String IDENTITY_PATCHES_FILE = "patches.txt";
    public static final String IDENTITY_UPDATES_FILE = "updates.txt";

    public static PatchRepository create(File root) {
        return new PatchRepository(root);
    }

    private final File root;
    private final File patchesDir;

    private PatchRepository(File root) {
        if (root == null) {
            throw new IllegalArgumentException("root is null");
        }
        this.root = root;
        patchesDir = new File(root, PATCHES);
    }

    public File getPatchesDir() {
        return patchesDir;
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

        final Patch patch = PatchUtil.readMetaData(patchFile);

        File targetFile = new File(patchesDir, patchFile.getName());
        if (targetFile.exists()) {
            final StringBuilder buf = new StringBuilder();
            buf.append(getPatchFileName(patch));
            int dot = patchFile.getName().lastIndexOf('.');
            if (dot < 0) {
                buf.append(".zip");
            } else {
                buf.append(patchFile.getName().substring(dot));
            }
            targetFile = new File(patchesDir, buf.toString());
            if (targetFile.exists()) {
                throw new PatchingException("File already exists in the repository" + targetFile.getAbsolutePath());
            }
        }

        try {
            IoUtils.copyFile(patchFile, targetFile);
        } catch (IOException e) {
            throw new PatchingException(
                    "Failed to copy " + patchFile.getAbsolutePath() + " to " + targetFile.getAbsolutePath(), e);
        }

        final File identityDir = new File(root, getIdentityDirName(patch));
        if (!identityDir.exists()) {
            if (!identityDir.mkdir()) {
                throw new PatchingException("Failed to create identity record directory " + identityDir.getAbsolutePath());
            }
        }

        if (patch.getIdentity().getPatchType() == PatchType.CUMULATIVE) {
            final File updateFile = new File(identityDir, IDENTITY_UPDATES_FILE);
            if (updateFile.exists()) {
                // for now i assume there could only be one update to the next micro version
                throw new PatchingException("There already is an update available for " + getIdentityDirName(patch));
            }
            appendFile(updateFile, targetFile.getName());
        } else if (patch.getIdentity().getPatchType() == PatchType.ONE_OFF) {
            appendFile(new File(identityDir, IDENTITY_PATCHES_FILE), targetFile.getName());
        } else {
            throw new PatchingException("Unexpected patch type " + patch.getIdentity().getPatchType());
        }
    }

    public boolean hasPatches(String identityName, String identityVersion) throws PatchingException {
        final File f = new File(getIdentityDir(identityName, identityVersion), IDENTITY_PATCHES_FILE);
        if (!f.exists()) {
            return false;
        }
        return !readList(f).isEmpty();
    }

    public List<Patch> getPatchesInfo(String identityName, String identityVersion) throws PatchingException {
        final File f = new File(getIdentityDir(identityName, identityVersion), IDENTITY_PATCHES_FILE);
        if (!f.exists()) {
            return Collections.emptyList();
        }
        final List<String> fileNames = readList(f);
        final List<Patch> patches = new ArrayList<Patch>(fileNames.size());
        for (String fileName : fileNames) {
            patches.add(readMetaData(fileName));
        }
        return patches;
    }

    public boolean hasUpdate(String identityName, String identityVersion) throws PatchingException {
        final File f = new File(getIdentityDir(identityName, identityVersion), IDENTITY_UPDATES_FILE);
        if (!f.exists()) {
            return false;
        }
        return !readList(f).isEmpty();
    }

    public File bundlePatches(String identityName, String identityVersion, File targetDir) throws PatchingException {

        if (targetDir == null) {
            throw new IllegalArgumentException("targetDir is null");
        }

        final PatchBundleBuilder bundleBuilder = PatchBundleBuilder.create();
        bundlePatches(identityName, identityVersion, bundleBuilder);

        ensureDir(targetDir);
        final File targetFile = new File(targetDir, getIdentityDirName(identityName, identityVersion) + "-" + PATCHES + ".zip");
        bundleBuilder.build(targetFile);
        return targetFile;
    }

    public void getUpdateToNext(String identityName, String identityVersion, boolean includePatches, File targetFile)
            throws PatchingException {

        final File update = getUpdateOnly(identityName, identityVersion);
        if(update == null) {
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
        bundleBuilder.build(targetFile);
    }

    public void getUpdateToLatest(String identityName, String identityVersion, boolean includePatches, File targetFile)
            throws PatchingException {
        getUpdate(identityName, identityVersion, null, includePatches, targetFile);
    }

    public void getUpdate(String identityName, String identityVersion, String toVersion, boolean includePatches, File targetFile)
            throws PatchingException {
        File update = getUpdateOnly(identityName, identityVersion);
        if(update == null) {
            return;
        }
        final PatchBundleBuilder bundleBuilder = PatchBundleBuilder.create();
        boolean reachedTargetVersion = false;
        while(update != null && !reachedTargetVersion) {
            bundleBuilder.add(update);
            final Patch updateMetaData = PatchUtil.readMetaData(update);
            identityName = updateMetaData.getIdentity().getName();
            identityVersion = updateMetaData.getIdentity().getVersion();
            if(identityVersion.equals(toVersion)) {
                reachedTargetVersion = true;
            } else {
                update = getUpdateOnly(identityName, identityVersion);
            }
        }
        if(!reachedTargetVersion) {
            throw new PatchingException("Failed to locate update path to " + toVersion + ", latest available is " + identityVersion);
        }
        if (includePatches) {
            bundlePatches(identityName, identityVersion, bundleBuilder);
        }
        bundleBuilder.build(targetFile);
    }

    /**
     * @param identityName
     * @param identityVersion
     * @param bundleBuilder
     * @throws PatchingException
     */
    private void bundlePatches(String identityName, String identityVersion, final PatchBundleBuilder bundleBuilder)
            throws PatchingException {
        final File f = new File(getIdentityDir(identityName, identityVersion), IDENTITY_PATCHES_FILE);
        if (!f.exists()) {
            return;
        }

        final List<String> fileNames = readList(f);
        if (fileNames.isEmpty()) {
            return;
        }

        for (String fileName : fileNames) {
            bundleBuilder.add(new File(patchesDir, fileName));
        }
    }

    private File getUpdateOnly(String identityName, String identityVersion) throws PatchingException {

        final File f = new File(getIdentityDir(identityName, identityVersion), IDENTITY_UPDATES_FILE);
        if (!f.exists()) {
            return null;
        }
        final List<String> updates = readList(f);
        if (updates.isEmpty()) {
            return null;
        }

        if(updates.size() > 1) {
            throw new PatchingException("There is more than one update for " + identityName + "-" + identityVersion + ": " + updates);
        }
        final File update = new File(patchesDir, updates.get(0));
        if(!update.isFile()) {
            throw new PatchingException("Referenced file does not exist " + update.getAbsolutePath());
        }
        return update;
    }

    private Patch readMetaData(String fileName) throws PatchingException {
        assert fileName != null : "fileName is null";
        return PatchUtil.readMetaData(new File(patchesDir, fileName));
    }

    /**
     * @param targetDir
     * @throws PatchingException
     */
    private void ensureDir(File targetDir) throws PatchingException {
        if (!targetDir.exists()) {
            if (targetDir.mkdirs()) {
                throw new PatchingException("Failed to create target directory " + targetDir.getAbsolutePath());
            }
        } else if (!targetDir.isDirectory()) {
            throw new PatchingException("Target path is not a directory " + targetDir.getAbsolutePath());
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

    private static String getIdentityDirName(Patch patch) {
        assert patch != null : "patch is null";
        return getIdentityDirName(patch.getIdentity().getName(), patch.getIdentity().getVersion());
    }

    private static String getIdentityDirName(String name, String version) {
        return name + '-' + version;
    }

    private static String getPatchFileName(Patch patch) {
        assert patch != null : "patch is null";
        final StringBuilder buf = new StringBuilder();
        buf.append(patch.getIdentity().getName()).append('-').append(patch.getIdentity().getVersion()).append('-')
                .append(patch.getPatchId());
        return buf.toString();
    }
}
