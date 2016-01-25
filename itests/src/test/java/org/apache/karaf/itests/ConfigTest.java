/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.itests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerMethod;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerMethod.class)
public class ConfigTest extends KarafTestSupport {

    @Test
    public void listCommand() throws Exception {
        String configListOutput = executeCommand("config:list", ADMIN_ROLE);
        System.out.println(configListOutput);
        assertFalse(configListOutput.isEmpty());
        assertTrue("config:list command should contain entry for shell bundle", configListOutput.contains("org.apache.karaf.shell.ssh"));
        configListOutput = executeCommand("config:list \"(service.pid=org.apache.karaf.features)\"", ADMIN_ROLE);
        System.out.println(configListOutput);
        assertFalse(configListOutput.isEmpty());
        assertTrue("config:list should contain response for org.apache.karaf.features", configListOutput.contains("org.apache.karaf.features"));
    }

    @Test
    public void listViaMBean() throws Exception {
        JMXConnector connector = null;
        try {
            connector = this.getJMXConnector();
            MBeanServerConnection connection = connector.getMBeanServerConnection();
            ObjectName name = new ObjectName("org.apache.karaf:type=config,name=root");
            List<String> configs = (List<String>) connection.invoke(name, "list", new Object[]{ }, new String[]{ });
            assertTrue(configs.size() > 0);
            assertTrue(configs.contains("org.apache.karaf.features"));
            Map<String, String> properties = (Map<String, String>) connection.invoke(name, "proplist", new Object[]{ "org.apache.karaf.features" }, new String[]{ "java.lang.String" });
            assertTrue(properties.keySet().size() > 0);
        } finally {
            if (connector != null)
                connector.close();
        }
    }

}
