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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jboss.as.patching.IoUtils;
import org.jboss.as.patching.metadata.Patch;
import org.jboss.as.patching.metadata.PatchXml;
import org.jboss.as.patching.util.PatchRepository;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 * @author Alexey Loubyansky
 */
public class AddPatchFileTestCase {

    private static final File tempDir = new File(System.getProperty("java.io.tmpdir"));
    private static final File testDir = new File(tempDir, UUID.randomUUID().toString());

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

    @Test
    public void testAddPatches() throws Exception {

        final File repoDir = new File(testDir, "repo");
        final PatchRepository repo = PatchRepository.create(repoDir);

        assertFalse(repo.hasPatches("product", "1.0.1"));
        assertFalse(repo.hasUpdate("product", "1.0.0"));

        final File oneoff1 = createPatch("product", "1.0.1", "oneoff1", "base-oneoff1");
        repo.addPatch(oneoff1);

        assertTrue(repo.hasPatches("product", "1.0.1"));
        assertFalse(repo.hasUpdate("product", "1.0.0"));

        List<Patch> patches = repo.getPatchesInfo("product", "1.0.1");
        assertEquals(1, patches.size());
        //System.out.println(repo.getPatchXml("product", "1.0.1", "oneoff1", false));

        final File oneoff2 = createPatch("product", "1.0.1", "oneoff2", "base-oneoff2");
        repo.addPatch(oneoff2);

        assertTrue(repo.hasPatches("product", "1.0.1"));
        assertFalse(repo.hasUpdate("product", "1.0.0"));

        patches = repo.getPatchesInfo("product", "1.0.1");
        assertEquals(2, patches.size());
        //System.out.println(repo.getPatchXml("product", "1.0.1", "oneoff2", false));

        final File update1 = createUpdate("product", "1.0.0", "1.0.1", "cp1", "base-cp1");
        repo.addPatch(update1);

        assertTrue(repo.hasPatches("product", "1.0.1"));
        assertTrue(repo.hasUpdate("product", "1.0.0"));

        patches = repo.getPatchesInfo("product", "1.0.1");
        assertEquals(2, patches.size());

//        repo.getUpdateToLatest("product", "1.0.0", true, new File("/home/olubyans/patches/tests/latest-update.zip"));
//        repo.getPatch("product", "1.0.0", "oneoff1", false, new File("/home/olubyans/patches/tests/oneoff1.zip"));
//        repo.getPatch("product", "1.0.0", "cp1", true, new File("/home/olubyans/patches/tests/cp1.zip"));
    }

    protected void assertContent(File f, String... lines) throws Exception {

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(f));
            String line = reader.readLine();
            int i = 0;
            while(line != null) {
                assertTrue(i < lines.length);
                assertEquals(lines[i++], line);
                line = reader.readLine();
            }
        } finally {
            IoUtils.safeClose(reader);
        }
    }

    protected File createPatch(String identityName, String identityVersion, String patchId, String elementId) {
        return createPatchFile(identityName, identityVersion, null, patchId, "base", elementId);
    }

    protected File createUpdate(String identityName, String identityVersion, String identityNextVersion, String patchId, String elementId) {
        return createPatchFile(identityName, identityVersion, identityNextVersion, patchId, "base", elementId);
    }

    private File createPatchFile(
            String identityName,
            String identityVersion, String identityNextVersion, String patchId,
            String layer, String patchElementId) {

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
        vars.put("patch-element-id", patchElementId);
        vars.put("layer", layer);

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
            addDirectoryToZip(new File(patchTemplateDir, "patch-element-id"), patchElementId, zipOut);
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
