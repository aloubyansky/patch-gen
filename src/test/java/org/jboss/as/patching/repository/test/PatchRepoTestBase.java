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

package org.jboss.as.patching.repository.test;

import static org.jboss.as.patching.IoUtils.copyStream;
import static org.jboss.as.patching.IoUtils.safeClose;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jboss.as.patching.IoUtils;
import org.jboss.as.patching.metadata.PatchXml;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;

/**
 *
 * @author Alexey Loubyansky
 */
public abstract class PatchRepoTestBase {

    private static final File tempDir = new File(System.getProperty("java.io.tmpdir"));
    protected static final File testDir = new File(tempDir, UUID.randomUUID().toString());
    private static final byte[] LN = System.getProperty("line.separator").getBytes();

    @BeforeClass
    public static void setup() throws Exception {
        if (testDir.exists()) {
            IoUtils.recursiveDelete(testDir);
        }
        testDir.mkdirs();
        if (!testDir.exists()) {
            throw new IllegalStateException("Failed to create test dir " + testDir.getAbsolutePath());
        }
    }

    @AfterClass
    public static void cleanup() throws Exception {
        IoUtils.recursiveDelete(testDir);
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

    protected File createPatch(String identityName, String identityVersion, String patchId, String elementId) {
        return createPatch(identityName, identityVersion, patchId, ElementSpec.create("base", elementId));
    }

    protected File createPatch(String identityName, String identityVersion, String patchId, ElementSpec e) {
        return createPatchFile(identityName, identityVersion, null, patchId, e);
    }

    protected File createUpdate(String identityName, String identityVersion, String identityNextVersion, String patchId, String elementId) {
        return createPatchFile(identityName, identityVersion, identityNextVersion, patchId, ElementSpec.create("base", elementId, true));
    }

    protected File createUpdate(String identityName, String identityVersion, String identityNextVersion, String patchId, ElementSpec e) {
        return createPatchFile(identityName, identityVersion, identityNextVersion, patchId, e);
    }

    private File createPatchFile(String identityName, String identityVersion, String identityNextVersion, String patchId, ElementSpec element) {

        final URL patchTemplateURL = getClass().getResource("patch-template");
        Assert.assertNotNull(patchTemplateURL);
        final File patchTemplateDir = new File(patchTemplateURL.getFile());

        final File patchFile = new File(testDir, identityName + '-' + identityVersion + '-' + patchId + ".zip");

        final Map<String,String> vars = new HashMap<String,String>();
        vars.put("patch-id", patchId);
        if(identityNextVersion == null) {
            vars.put("patch-type", "no-upgrade");
            vars.put("to-version", null);
        } else {
            vars.put("patch-type", "upgrade");
            vars.put("to-version", "to-version=\"" + identityNextVersion + "\"");
        }
        vars.put("identity-name", identityName);
        vars.put("identity-version", identityVersion);
        vars.put("patch-element-id", element.id);
        vars.put("layer", element.providerName);
        vars.put("add-on", element.addon ? "add-on=\"true\"" : null);
        vars.put("element-patch-type", element.update ? "upgrade" : "no-upgrade");

        ZipOutputStream zipOut = null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(new File(patchTemplateDir, PatchXml.PATCH_XML)));
            zipOut = new ZipOutputStream(new FileOutputStream(patchFile));
            zipOut.putNextEntry(new ZipEntry(PatchXml.PATCH_XML));

            String line = reader.readLine();
            while (line != null) {
                int at = line.indexOf('@');
                if (at >= 0) {
                    final StringBuilder buf = new StringBuilder();
                    int from = 0;
                    while (at >= 0) {
                        buf.append(line.substring(from, at));

                        boolean replaced = false;
                        for(String name : vars.keySet()) {
                            if(line.startsWith(name, at + 1)) {
                                final String value = vars.get(name);
                                if(value != null) {
                                    buf.append(value);
                                }
                                from = at + name.length() + 1;
                                replaced = true;
                                break;
                            }
                        }
                        if(!replaced) {
                            from = at;
                        }
                        at = line.indexOf('@', at + 1);
                    }
                    if(from < line.length() - 1) {
                        buf.append(line.substring(from));
                    }
                    line = buf.toString();
                }
                zipOut.write(line.getBytes());
                zipOut.write(LN);
                line = reader.readLine();
            }
            zipOut.closeEntry();

            addDirectoryToZip(new File(patchTemplateDir, "patch-id"), patchId, zipOut);
            addDirectoryToZip(new File(patchTemplateDir, "patch-element-id"), element.id, zipOut);
        } catch (FileNotFoundException e) {
            throw new IllegalStateException("Failed to write to " + patchFile.getAbsolutePath(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write to " + patchFile.getAbsolutePath(), e);
        } finally {
            IoUtils.safeClose(reader);
            IoUtils.safeClose(zipOut);
        }

        return patchFile;
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

    protected static class ElementSpec {

            static ElementSpec create(String providerName, String elementId) {
                return new ElementSpec(providerName, false, elementId);
            }

            static ElementSpec create(String providerName, String elementId, boolean update) {
                return new ElementSpec(providerName, false, elementId, update);
            }

            static ElementSpec create(String providerName, boolean addon, String elementId) {
                return new ElementSpec(providerName, addon, elementId);
            }

            static ElementSpec create(String providerName, boolean addon, String elementId, boolean update) {
                return new ElementSpec(providerName, addon, elementId, update);
            }

            final String providerName;
            final boolean addon;
            final String id;
            final boolean update;

            private ElementSpec(String providerName, boolean addon, String elementId) {
                this(providerName, addon, elementId, false);
            }

            private ElementSpec(String providerName, boolean addon, String elementId, boolean update) {

                if(providerName == null) {
                    throw new IllegalArgumentException("providerName is null");
                }
                if(elementId == null) {
                    throw new IllegalArgumentException("elementId is null");
                }
                this.providerName = providerName;
                this.addon = addon;
                this.id = elementId;
                this.update = update;
            }
        }

    /**
     *
     */
    public PatchRepoTestBase() {
        super();
    }

}