/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.admin.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import junit.framework.TestCase;
import org.apache.karaf.admin.Instance;
import org.apache.karaf.admin.InstanceSettings;

public class AdminServiceImplTest extends TestCase {

    public void testHandleFeatures() throws Exception {
        AdminServiceImpl as = new AdminServiceImpl();
        
        File f = File.createTempFile(getName(), ".test");
        try {
            Properties p = new Properties();
            p.put("featuresBoot", "abc,def ");
            p.put("featuresRepositories", "somescheme://xyz");
            OutputStream os = new FileOutputStream(f);
            try {
                p.store(os, "Test comment");
            } finally {
                os.close();
            }
            
            InstanceSettings s = new InstanceSettings(8122, 1122, 44444, null, null, null, Arrays.asList("test"));
            as.handleFeatures(f, s);
            
            Properties p2 = new Properties();
            InputStream is = new FileInputStream(f);
            try {
                p2.load(is);
            } finally {
                is.close();
            }
            assertEquals(2, p2.size());
            assertEquals("abc,def,test", p2.get("featuresBoot"));
            assertEquals("somescheme://xyz", p2.get("featuresRepositories"));
        } finally {
            f.delete();
        }
    }

    /**
     * Ensure the admin:create generates all the required configuration files
     * //TODO: fix this test so it can run in an IDE
     */
    public void testConfigurationFiles() throws Exception {
        AdminServiceImpl service = new AdminServiceImpl();
        service.setStorageLocation(new File("target/instances/" + System.currentTimeMillis()));

        InstanceSettings settings = new InstanceSettings(8122, 1122, 44444, getName(), null, null, null);
        Instance instance = service.createInstance(getName(), settings);

        assertFileExists(instance.getLocation(), "etc/config.properties");
        assertFileExists(instance.getLocation(), "etc/users.properties");
        assertFileExists(instance.getLocation(), "etc/startup.properties");

        assertFileExists(instance.getLocation(), "etc/java.util.logging.properties");
        assertFileExists(instance.getLocation(), "etc/org.apache.karaf.features.cfg");
        assertFileExists(instance.getLocation(), "etc/org.apache.felix.fileinstall-deploy.cfg");
        assertFileExists(instance.getLocation(), "etc/org.apache.karaf.log.cfg");
        assertFileExists(instance.getLocation(), "etc/org.apache.karaf.management.cfg");
        assertFileExists(instance.getLocation(), "etc/org.ops4j.pax.logging.cfg");
        assertFileExists(instance.getLocation(), "etc/org.ops4j.pax.url.mvn.cfg");
    }

    public void testTextResources() throws Exception {
        AdminServiceImpl service = new AdminServiceImpl();
        service.setStorageLocation(new File("target/instances/" + System.currentTimeMillis()));
        Map<String, URL> textResources = new HashMap<String, URL>();
        textResources.put("etc/myresource", getClass().getClassLoader().getResource("myresource"));

        InstanceSettings settings = new InstanceSettings(8122, 1122, 44444, getName(), null, null, null, textResources, new HashMap<String, URL>());
        Instance instance = service.createInstance(getName(), settings);

        assertFileExists(instance.getLocation(), "etc/myresource");
    }

    /**
     * <p>
     * Test the renaming of an existing instance.
     * </p>
     */
    public void testRenameInstance() throws Exception {
        AdminServiceImpl service = new AdminServiceImpl();
        service.setStorageLocation(new File("target/instances/" + System.currentTimeMillis()));

        InstanceSettings settings = new InstanceSettings(8122, 1122, 44444, getName(), null, null, null);
        Instance instance = service.createInstance(getName(), settings);

        service.renameInstance(getName(), getName() + "b");
        assertNotNull(service.getInstance(getName() + "b"));
    }
    
    /**
     * <p>
     * Test the renaming of an existing instance.
     * </p>
     */
    public void testToSimulateRenameInstanceByExternalProcess() throws Exception {
        AdminServiceImpl service = new AdminServiceImpl();
        File storageLocation = new File("target/instances/" + System.currentTimeMillis());
        service.setStorageLocation(storageLocation);

        InstanceSettings settings = new InstanceSettings(8122, 1122, 44444, getName(), null, null, null);
        service.createInstance(getName(), settings);
        
        //to simulate the scenario that the instance name get changed by 
        //external process, likely the admin command CLI tool, which cause
        //the instance storage file get updated, the AdminService should be 
        //able to reload the storage file before check any status for the 
        //instance
        
        File storageFile = new File(storageLocation, AdminServiceImpl.STORAGE_FILE);
        assertTrue(storageFile.isFile());
        Properties storage = loadStorage(storageFile);
        storage.setProperty("item.0.name", getName() + "b");
        saveStorage(storage, storageFile, "testToSimulateRenameInstanceByExternalProcess");
        
        assertNotNull(service.getInstance(getName() + "b"));
    }

    public void testExtractZookeeperCredentials() {
        List<String> credentials = new ArrayList<>(3);
        String options = AdminServiceImpl.extractZookeeperCredentials("-Dx=y -Dzookeeper.url=test", credentials);
        assertEquals(1, credentials.size());
        assertEquals("test", credentials.get(0));
        assertEquals("-Dx=y ", options);

        options = AdminServiceImpl.extractZookeeperCredentials("-Dx=y -Dzookeeper.url = test ", credentials);
        assertEquals(1, credentials.size());
        assertEquals("test", credentials.get(0));
        assertEquals("-Dx=y ", options);

        options = AdminServiceImpl.extractZookeeperCredentials("-Dx=y -Dzookeeper.url = \"test \" ", credentials);
        assertEquals(1, credentials.size());
        assertEquals("test ", credentials.get(0));
        assertEquals("-Dx=y ", options);

        options = AdminServiceImpl.extractZookeeperCredentials("-Dx=y -Dzookeeper.url = \"test \\\"\" ", credentials);
        assertEquals(1, credentials.size());
        assertEquals("test \\\"", credentials.get(0));
        assertEquals("-Dx=y ", options);

        options = AdminServiceImpl.extractZookeeperCredentials("-Dx=y -Dzookeeper.url = \" test \\\"\" ", credentials);
        assertEquals(1, credentials.size());
        assertEquals(" test \\\"", credentials.get(0));
        assertEquals("-Dx=y ", options);

        options = AdminServiceImpl.extractZookeeperCredentials("-Dx=y -Dzookeeper.url = \" test \\\"\" -Dzookeeper.password=abcd -Dzookeeper.password.encode=true -Da=b ", credentials);
        assertEquals(3, credentials.size());
        assertEquals(" test \\\"", credentials.get(0));
        assertEquals("true", credentials.get(1));
        assertEquals("abcd", credentials.get(2));
        assertEquals("-Dx=y -Da=b", options);

        options = AdminServiceImpl.extractZookeeperCredentials("-server -Dcom.sun.management.jmxremote -Dorg.jboss.gravia.repository.storage.dir=data/repository -Dzookeeper.url=\"everfree.forest:2181\" -Dzookeeper.password.encode=\"true\" -Dzookeeper.password=\"admin\" -Xmx768m -XX:+UnlockDiagnosticVMOptions -XX:+UnsyncloadClass -Dio.fabric8.datastore.gitTimeout=40 -Dio.fabric8.datastore.component.name=io.fabric8.datastore -Dio.fabric8.datastore.felix.fileinstall.filename=file:/data/servers/jboss-fuse-6.3.0.redhat-351/etc/io.fabric8.datastore.cfg -Dio.fabric8.datastore.importDir=fabric -Dio.fabric8.datastore.gitAllowRemoteUpdate=true -Dio.fabric8.datastore.gitRandomFetchDelay=0 -Dio.fabric8.datastore.gitRemotePollInterval=60000 -Dio.fabric8.datastore.service.pid=io.fabric8.datastore", credentials);
        assertEquals(3, credentials.size());
        assertEquals("everfree.forest:2181", credentials.get(0));
        assertEquals("true", credentials.get(1));
        assertEquals("admin", credentials.get(2));
        assertEquals("-server -Dcom.sun.management.jmxremote -Dorg.jboss.gravia.repository.storage.dir=data/repository -Xmx768m -XX:+UnlockDiagnosticVMOptions -XX:+UnsyncloadClass -Dio.fabric8.datastore.gitTimeout=40 -Dio.fabric8.datastore.component.name=io.fabric8.datastore -Dio.fabric8.datastore.felix.fileinstall.filename=file:/data/servers/jboss-fuse-6.3.0.redhat-351/etc/io.fabric8.datastore.cfg -Dio.fabric8.datastore.importDir=fabric -Dio.fabric8.datastore.gitAllowRemoteUpdate=true -Dio.fabric8.datastore.gitRandomFetchDelay=0 -Dio.fabric8.datastore.gitRemotePollInterval=60000 -Dio.fabric8.datastore.service.pid=io.fabric8.datastore", options);
    }

    private void saveStorage(Properties props, File location, String comment) throws IOException {
        OutputStream os = null;
        try {
            os = new FileOutputStream(location);
            props.store(os, comment);
        } finally {
            if (os != null) {
                os.close();
            }
        }
    }
    
    private Properties loadStorage(File location) throws IOException {
        InputStream is = null;
        try {
            is = new FileInputStream(location);
            Properties props = new Properties();
            props.load(is);
            return props;
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    private void assertFileExists(String path, String name) throws IOException {
        File file = new File(path, name);
        assertTrue("Expected " + file.getCanonicalPath() + " to exist",
                   file.exists());
    }   
}
