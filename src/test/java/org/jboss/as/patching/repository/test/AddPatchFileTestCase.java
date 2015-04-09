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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jboss.as.patching.IoUtils;
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

        if(repo.getPatchesDir().exists()) {
            assertEquals(0, repo.getPatchesDir().list().length);
        }
        final File p100 = repo.getIdentityDir("product", "1.0.0");
        assertFalse(p100.exists());

        final File oneoff1 = createPatch("oneoff1", "product", "1.0.0");
        repo.addPatch(oneoff1);

        assertTrue(repo.getPatchesDir().exists());
        assertTrue(p100.exists());

        final File p100Patches = new File(p100, PatchRepository.IDENTITY_PATCHES_FILE);
        final File p100Updates = new File(p100, PatchRepository.IDENTITY_UPDATES_FILE);

        assertEquals(1, repo.getPatchesDir().list().length);
        assertTrue(p100Patches.exists());
        assertContent(p100Patches, oneoff1.getName());
        assertFalse(p100Updates.exists());

        final File oneoff2 = createPatch("oneoff2", "product", "1.0.0");
        repo.addPatch(oneoff2);

        assertEquals(2, repo.getPatchesDir().list().length);
        assertContent(p100Patches, oneoff1.getName(), oneoff2.getName());
        assertFalse(p100Updates.exists());

        final File update1 = createUpdate("cp1", "product", "1.0.0", "1.0.1");
        repo.addPatch(update1);

        assertEquals(3, repo.getPatchesDir().list().length);
        assertTrue(p100Updates.exists());
        assertContent(p100Updates, update1.getName());
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

    protected File createPatch(String patchId, String identityName, String identityVersion) {
        return createPatchFile(getClass().getResourceAsStream("patch-template-patch.xml"), patchId, identityName, identityVersion, null);
    }

    protected File createUpdate(String patchId, String identityName, String identityVersion, String identityNextVersion) {
        return createPatchFile(getClass().getResourceAsStream("update-template-patch.xml"), patchId, identityName, identityVersion, identityNextVersion);
    }

    /**
     * @param is
     * @param patchId
     * @param identityName
     * @param identityVersion
     * @return
     */
    private File createPatchFile(final InputStream is, String patchId, String identityName, String identityVersion, String identityNextVersion) {
        Assert.assertNotNull(is);
        final BufferedReader reader = new BufferedReader(new InputStreamReader(is));

        final File patchFile = new File(testDir, identityName + '-' + identityVersion + '-' + patchId + ".zip");
        ZipOutputStream zipOut = null;
        try {
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
                        if (line.startsWith("patch-id", at + 1)) {
                            buf.append(patchId);
                            from = at + "patch-id".length() + 1;
                        } else if (line.startsWith("identity-name", at + 1)) {
                            buf.append(identityName);
                            from = at + "identity-name".length() + 1;
                        } else if (line.startsWith("identity-version", at + 1)) {
                            buf.append(identityVersion);
                            from = at + "identity-version".length() + 1;
                        } else if(identityNextVersion != null && line.startsWith("identity-next-version", at + 1)) {
                            buf.append(identityNextVersion);
                            from = at + "identity-next-version".length() + 1;
                        } else {
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
}
