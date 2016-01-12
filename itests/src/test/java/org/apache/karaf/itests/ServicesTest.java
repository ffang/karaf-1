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

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.TabularData;
import javax.management.remote.JMXConnector;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerMethod;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerMethod.class)
public class ServicesTest extends KarafTestSupport {

    @Test
    public void listCommand() throws Exception {
        String listOutput = executeCommand("ls", ADMIN_ROLE);
        System.out.println(listOutput);
        assertFalse(listOutput.isEmpty());
        assertTrue("ls should contain package org.apache.karaf.jaas.modules in list of services",
                countMatches(".*org.apache.karaf.jaas.modules.*", listOutput) > 0);
    }

    @Test
    public void listViaMBean() throws Exception {
        JMXConnector connector = null;
        try {
            connector = this.getJMXConnector();
            MBeanServerConnection connection = connector.getMBeanServerConnection();
            ObjectName name = new ObjectName("org.apache.karaf:type=services,name=root");
            TabularData services = (TabularData) connection.invoke(name, "list", new Object[]{ }, new String[]{ });
            assertTrue(services.size() > 0);
            assertTrue("JMX list should contain package org.apache.karaf.jaas.modules in list of services",
                    countMatches(".*org.apache.karaf.jaas.modules.*", services.toString()) > 0);

        } finally {
            if (connector != null)
                connector.close();
        }
    }

}
