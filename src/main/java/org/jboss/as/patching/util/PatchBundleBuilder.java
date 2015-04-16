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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jboss.as.patching.IoUtils;
import org.jboss.as.patching.PatchingException;
import org.jboss.as.patching.metadata.BundledPatch;
import org.jboss.as.patching.metadata.Patch;
import org.jboss.as.patching.metadata.PatchBundleXml;

/**
 *
 * @author Alexey Loubyansky
 */
public class PatchBundleBuilder {

    public static PatchBundleBuilder create() {
        return new PatchBundleBuilder();
    }

    private List<File> files = Collections.emptyList();

    private PatchBundleBuilder() {
    }

    public PatchBundleBuilder add(File patchFile) throws PatchingException {

        switch (files.size()) {
            case 0:
                files = Collections.singletonList(patchFile);
                break;
            case 1:
                files = new ArrayList<File>(files);
            default:
                files.add(patchFile);
        }

        return this;
    }

    public BundledPatch build(File targetFile, boolean deleteFiles) throws PatchingException {

        if (targetFile == null) {
            throw new IllegalArgumentException("targetFile is null");
        }
        if (targetFile.isDirectory()) {
            throw new PatchingException("Target file is a directory " + targetFile.getAbsolutePath());
        }

        if (files.isEmpty()) {
            return null;
        }

        ZipOutputStream zipOut = null;

        final List<BundledPatch.BundledPatchEntry> bundleEntries = new ArrayList<BundledPatch.BundledPatchEntry>(files.size());

        try {
            zipOut = new ZipOutputStream(new FileOutputStream(targetFile));

            for (File patchFile : files) {
                if (patchFile == null) {
                    throw new IllegalArgumentException("patchFile is null");
                }
                if (!patchFile.exists()) {
                    throw new PatchingException("Referenced patch file does not exist " + patchFile.getAbsolutePath());
                }

                final Patch patch = PatchUtil.readMetaData(patchFile);
                bundleEntries.add(new BundledPatch.BundledPatchEntry(patch.getPatchId(), patchFile.getName()));

                FileInputStream patchIs = null;
                try {
                    zipOut.putNextEntry(new ZipEntry(patchFile.getName()));
                    patchIs = new FileInputStream(patchFile);
                    IoUtils.copyStream(patchIs, zipOut);
                    zipOut.closeEntry();
                } catch (IOException e) {
                    throw new PatchingException("Failed to bundle " + patchFile.getAbsolutePath(), e);
                }
            }

            zipOut.putNextEntry(new ZipEntry(PatchBundleXml.MULTI_PATCH_XML));
            final BundledPatch metadata = new BundledPatch() {
                @Override
                public List<BundledPatchEntry> getPatches() {
                    return bundleEntries;
                }
            };
            PatchBundleXml.marshal(zipOut, metadata);
            zipOut.closeEntry();
            return metadata;
        } catch (FileNotFoundException e) {
            throw new PatchingException("Failed to create (or open for writing) file " + targetFile.getAbsolutePath(), e);
        } catch (Exception e) {
            throw new PatchingException("Failed to write " + PatchBundleXml.MULTI_PATCH_XML, e);
        } finally {
            IoUtils.safeClose(zipOut);
            if(deleteFiles) {
                for(File f : files) {
                    if(!f.delete()) {
                        f.deleteOnExit();
                    }
                }
            }
        }
    }
}
