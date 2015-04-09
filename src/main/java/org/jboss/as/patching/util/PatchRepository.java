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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLStreamException;

import org.jboss.as.patching.IoUtils;
import org.jboss.as.patching.PatchingException;
import org.jboss.as.patching.logging.PatchLogger;
import org.jboss.as.patching.metadata.Patch;
import org.jboss.as.patching.metadata.Patch.PatchType;
import org.jboss.as.patching.metadata.PatchXml;


/**
 * Patch repository.
 *
 * Every patch file added to the repository is copied to ROOT/patches directory.
 *
 * When a new patch is added, a new directory ROOT/identity-name-version corresponding
 * to the target identity of the patch is created (unless one already exists).
 * If the patch is a one-off patch, the name of the patch file is added to
 * ROOT/identity-name-version/patches.txt.
 * If the patch is a cumulative patch, the name of the patch file is added to
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
        if(root == null) {
            throw new IllegalArgumentException("root is null");
        }
        this.root = root;
        patchesDir = new File(root, PATCHES);
    }

    public File getPatchesDir() {
        return patchesDir;
    }

    public File getIdentityDir(String name, String version) {
        return new File(root, getIdentityDirName(name, version));
    }

    public void addPatch(File patchFile) throws PatchingException {

        if(patchFile == null) {
            throw new IllegalArgumentException("patchFile is null");
        }

        if(!patchFile.exists()) {
            throw new PatchingException(PatchLogger.ROOT_LOGGER.fileDoesNotExist(patchFile.getAbsolutePath()));
        }
        if(patchFile.isDirectory()) {
            throw new PatchingException(PatchLogger.ROOT_LOGGER.fileIsADirectory(patchFile.getAbsolutePath()));
        }

        Patch patch;
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(patchFile);
            final ZipEntry patchXmlEntry = zipFile.getEntry(PatchXml.PATCH_XML);
            if(patchXmlEntry == null) {
                PatchLogger.ROOT_LOGGER.patchIsMissingFile(PatchXml.PATCH_XML);
            }

            final InputStream patchXmlIs = zipFile.getInputStream(patchXmlEntry);
            try {
                patch = PatchXml.parse(patchXmlIs).resolvePatch(null, null);
            } catch (XMLStreamException e) {
                throw new PatchingException("Failed to parse " + PatchXml.PATCH_XML + " from " + patchFile.getAbsolutePath(), e);
            } finally {
                IoUtils.safeClose(patchXmlIs);
            }
        } catch (ZipException e) {
            throw new PatchingException(PatchLogger.ROOT_LOGGER.fileIsNotReadable(patchFile.getAbsolutePath()));
        } catch (IOException e) {
            throw new PatchingException(PatchLogger.ROOT_LOGGER.fileIsNotReadable(patchFile.getAbsolutePath()));
        } finally {
            IoUtils.safeClose(zipFile);
        }

        File targetFile = new File(patchesDir, patchFile.getName());
        if(targetFile.exists()) {
            final StringBuilder buf = new StringBuilder();
            buf.append(getPatchFileName(patch));
            int dot = patchFile.getName().lastIndexOf('.');
            if(dot < 0) {
                buf.append(".zip");
            } else {
                buf.append(patchFile.getName().substring(dot));
            }
            targetFile = new File(patchesDir, buf.toString());
            if(targetFile.exists()) {
                throw new PatchingException("File already exists in the repository" + targetFile.getAbsolutePath());
            }
        }

        try {
            IoUtils.copyFile(patchFile, targetFile);
        } catch (IOException e) {
            throw new PatchingException("Failed to copy " + patchFile.getAbsolutePath() + " to " + targetFile.getAbsolutePath(), e);
        }

        final File identityDir = new File(root, getIdentityDirName(patch));
        if(!identityDir.exists()) {
            if(!identityDir.mkdir()) {
                throw new PatchingException("Failed to create identity record directory " + identityDir.getAbsolutePath());
            }
        }

        if(patch.getIdentity().getPatchType() == PatchType.CUMULATIVE) {
            final File updateFile = new File(identityDir, IDENTITY_UPDATES_FILE);
            if(updateFile.exists()) {
                // for now i assume there could only be one update to the next micro version
                throw new PatchingException("There already is an update available for " + getIdentityDirName(patch));
            }
            appendFile(updateFile, targetFile.getName());
        } else if(patch.getIdentity().getPatchType() == PatchType.ONE_OFF) {
            appendFile(new File(identityDir, IDENTITY_PATCHES_FILE), targetFile.getName());
        } else {
            throw new PatchingException("Unexpected patch type " + patch.getIdentity().getPatchType());
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
        } catch(IOException e) {
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
        buf.append(patch.getIdentity().getName())
            .append('-').append(patch.getIdentity().getVersion())
            .append('-').append(patch.getPatchId());
        return buf.toString();
    }
}
