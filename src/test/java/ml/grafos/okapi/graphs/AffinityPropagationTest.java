/**
 * Copyright 2014 Grafos.ml
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ml.grafos.okapi.graphs;

import org.apache.giraph.conf.GiraphConfiguration;
import org.apache.giraph.utils.InternalVertexRunner;
import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.fail;

public class AffinityPropagationTest {
  private GiraphConfiguration conf;

  @Before
  public void initialize() {
    conf = new GiraphConfiguration();
    conf.setComputationClass(AffinityPropagation.class);
    conf.setMasterComputeClass(AffinityPropagation.MasterComputation.class);
    conf.setInt(AffinityPropagation.MAX_ITERATIONS, 100);
    conf.setFloat(AffinityPropagation.DAMPING, 0.9f);
    conf.setVertexOutputFormatClass(AffinityPropagation.APOutputFormat.class);
    conf.setBoolean("giraph.useSuperstepCounters", false);
  }

  @Test
  public void testVertexInput() {
    String[] graph = {
      "1 1 1 5",
      "2 1 1 3",
      "3 5 3 1",
    };

    conf.setVertexInputFormatClass(AffinityPropagation.APVertexInputFormatter.class);

    Iterable<String> results;
    try {
      results = InternalVertexRunner.run(conf, graph, null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    System.err.println(results);
  }

  @Test
  public void testEdgeInput() {
    String[] graph = {
      "1 1 1",
      "1 2 1",
      "1 3 5",
      "2 1 1",
      "2 2 1",
      "2 3 3",
      "3 1 5",
      "3 2 3",
      "3 3 1",
    };

    conf.setEdgeInputFormatClass(AffinityPropagation.APEdgeInputFormatter.class);

    Iterable<String> results;
    try {
      results = InternalVertexRunner.run(conf, null, graph);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    System.err.println(results);
  }

  @Test
  public void testSparse() {
    String[] graph = {
      "1 1 1",
      "1 2 1",
      "1 3 5",
      "2 1 1",
      "2 2 1",
      "3 1 5",
      "3 3 1",
    };

    conf.setEdgeInputFormatClass(AffinityPropagation.APEdgeInputFormatter.class);

    Iterable<String> results;
    try {
      results = InternalVertexRunner.run(conf, null, graph);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    System.err.println(results);
  }

  @Test
  public void testToyProblem() throws IOException {
    InputStream is = getClass().getResourceAsStream("/ap/toyProblem.txt");
    BufferedReader br = new BufferedReader(new InputStreamReader(is));

    String line;
    List<String> lines = new ArrayList<String>();
    while((line = br.readLine()) != null) {
      lines.add(line);
    }

    String[] graph = lines.toArray(new String[0]);

    conf.setEdgeInputFormatClass(AffinityPropagation.APEdgeInputFormatter.class);

    Iterable<String> results;
    try {
      results = InternalVertexRunner.run(conf, null, graph);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    System.err.println(results);
  }

}
