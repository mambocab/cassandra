/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.modules;

import java.io.FileNotFoundException;
import java.util.concurrent.Future;

import org.apache.cassandra.HarnessContext;
import org.apache.cassandra.htest.Config;

public abstract class AbstractStressModule extends Module
{
    protected String settings;

    public AbstractStressModule(Config config, HarnessContext context, String settings)
    {
        super(config, context);
        this.settings = settings;
    }

    public Future validate()
    {
        return newTask(new StressTask());
    }

    class StressTask implements Runnable
    {
        public void run()
        {
            String output = bridge.stress(settings);
            handleOutput(output);
        }

        private void handleOutput(String output)
        {
            //TODO: Return only the subset of output w/ the failure
            if (output.contains("Exception"))
            {
                harness.signalFailure(this.getClass().getName(), output);
            }

            if (output.contains("Data returned was not validated"))
            {
                harness.signalFailure(this.getClass().getName(), output);
            }
        }
    }
}
