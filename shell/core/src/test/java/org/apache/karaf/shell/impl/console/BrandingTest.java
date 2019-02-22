/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.karaf.shell.impl.console;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.Assert.*;

public class BrandingTest {

    /**
     * ENTESB-9765 Test that welcome text displays port read from etc/org.ops4j.pax.web.cfg instead from the default
     * 8181 value
     */
    @Test
    public void testWelcomeTextDisplaysCorrectPort() {
        Path workdir = Paths.get("").toAbsolutePath();
        Path etc = Paths.get(getClass().getClassLoader().getResource("etc").getFile());

        System.setProperty("karaf.etc", workdir.relativize(etc).toString());

        Properties properties = Branding.loadBrandingProperties(false);
        String welcomeText = properties.getProperty("welcome");
        assertFalse(welcomeText.contains("http://localhost:8181/hawtio"));
        assertTrue(welcomeText.contains("http://localhost:7777/hawtio"));
    }

}
