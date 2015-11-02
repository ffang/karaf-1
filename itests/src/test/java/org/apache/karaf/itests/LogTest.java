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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import javax.management.Attribute;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;

import org.apache.karaf.jaas.boot.principal.RolePrincipal;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerMethod;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerMethod.class)
public class LogTest extends KarafTestSupport {

    @Test
    public void setDebugAndDisplay() throws Exception {
        System.out.println(executeCommand("log:set DEBUG", new RolePrincipal("admin")));
        String displayOutput = executeCommand("log:display", new RolePrincipal("admin"));
        System.out.println(displayOutput);
        assertTrue(displayOutput.contains("DEBUG"));
    }

    @Test
    public void setDebugViaMBean() throws Exception {
        JMXConnector connector = null;
        try {
            connector = this.getJMXConnector();
            MBeanServerConnection connection = connector.getMBeanServerConnection();
            ObjectName name = new ObjectName("org.apache.karaf:type=log,name=root");
            Attribute attribute = new Attribute("Level", "DEBUG");
            connection.setAttribute(name, attribute);
            String logLevel = (String) connection.getAttribute(name, "Level");
            assertEquals("Level: DEBUG", logLevel);
        } finally {
            if (connector != null)
                connector.close();
        }
    }

    @Test
    public void setGetDebugAndClear() throws Exception {
        System.out.println(executeCommand("log:set DEBUG", new RolePrincipal("admin")));
        String getOutput = executeCommand("log:get", new RolePrincipal("admin"));
        System.out.println(getOutput);
        assertTrue(getOutput.contains("DEBUG"));
        System.out.println(executeCommand("log:set INFO", new RolePrincipal("admin")));
        System.out.println(executeCommand("log:clear", new RolePrincipal("admin")));
        String displayOutput = executeCommand("log:display", new RolePrincipal("admin"));
        System.out.println(displayOutput.trim());
        assertTrue(displayOutput.trim().isEmpty());
    }

}
