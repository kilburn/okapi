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

import es.csic.iiia.bms.CommunicationAdapter;
import es.csic.iiia.bms.Factor;
import es.csic.iiia.bms.MaxOperator;
import es.csic.iiia.bms.Maximize;
import es.csic.iiia.bms.factors.*;
import org.apache.giraph.aggregators.LongMaxAggregator;
import org.apache.giraph.graph.BasicComputation;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.io.formats.TextVertexValueInputFormat;
import org.apache.giraph.master.DefaultMasterCompute;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Affinity Propagation is a clustering algorithm.
 *
 * The number of clusters is not received as an input, but computed by the
 * algorithm according to the distances between points and the preference
 * of each node to be an <i>exemplar</i> (the "leader" of a cluster).
 *
 * You can find a detailed description of the algorithm in the affinity propagation
 * <a href="http://genes.toronto.edu/index.php?q=affinity%20propagation">website</a>.
 * 
 * @author Marc Pujol-Gonzalez <mpujol@iiia.csic.es>
 * @author Toni Penya-Alba <tonipenya@iiia.csic.es>
 *
 */
public class AffinityPropagation
    extends BasicComputation<AffinityPropagation.APVertexID,
    DoubleWritable, FloatWritable, AffinityPropagation.APMessage> {

  private static MaxOperator MAX_OPERATOR = new Maximize();

  public static int MAX_ITERATIONS = 10;

  @Override
  public void compute(Vertex<APVertexID, DoubleWritable, FloatWritable> vertex,
                      Iterable<APMessage> messages) throws IOException {

    final APVertexID id = vertex.getId();

    // In the first step, compute the number of rows and columns
    if (getSuperstep() == 0) {
      aggregate("nRows", new LongWritable(id.row));
      aggregate("nColumns", new LongWritable(id.column));
      return;
    }

    LongWritable aggregatedRows = getAggregatedValue("nRows");
    final long nRows = aggregatedRows.get();
    LongWritable aggregatedColumns = getAggregatedValue("nRows");
    final long nColumns = aggregatedColumns.get();
    if (nRows != nColumns) {
      throw new IllegalStateException("The input must form a square matrix, but we got " +
      nRows + " rows and " + nColumns + "columns.");
    }

    System.err.println("Number of rows: " + nRows);
    System.err.println("Number of columns: " + nColumns);

    // Build a factor of the required type
    Factor<APVertexID> factor;
    switch (id.type) {
      case VARIABLE:
        Factor<APVertexID> variable = new VariableFactor<APVertexID>();
        SingleWeightFactor<APVertexID> node = new SingleWeightFactor<APVertexID>(variable);
        node.setPotential(vertex.getValue().get());
        factor = node;
        break;

      case CONSISTENCY:
        ConditionedDeactivationFactor<APVertexID> node2 = new ConditionedDeactivationFactor<APVertexID>();
        node2.setExemplar(new APVertexID(VertexType.VARIABLE, id.column, id.column));
        factor = node2;
        break;

      case SELECTOR:
        factor = new SelectorFactor<APVertexID>();
        break;

      default:
        throw new IllegalStateException("Unrecognized node type " + id.type);
    }

    // Initialize it with proper values
    MessageCollector collector = new MessageCollector();
    factor.setCommunicationAdapter(collector);
    factor.setIdentity(id);
    factor.setMaxOperator(MAX_OPERATOR);

    // Compute the factor's neighbors (we do not have edges because this is a very dense
    // graph and thus its better to avoid creating that many giraph edges).
    Collection<APVertexID> neighbors = getNeighbors(id);

    // Receive messages and compute
    for (APMessage message : messages) {
      factor.receive(message.value, message.from);
    }
    factor.run();

    if (getSuperstep() >= MAX_ITERATIONS) {
      vertex.voteToHalt();
    }

    vertex.voteToHalt();
  }

  /**
   * TODO: implement this.
   * @param id
   * @return
   */
  public static Collection<APVertexID> getNeighbors(APVertexID id) {
    return null;
  }

  public static enum VertexType {
    VARIABLE, SIMILARITY, CONSISTENCY, SELECTOR
  }

  public static class APVertexID implements WritableComparable<APVertexID> {

    public VertexType type = VertexType.VARIABLE;
    public long row = 0;
    public long column = 0;

    public APVertexID() {}

    public APVertexID(VertexType type, long row, long column) {
      this.type = type;
      this.row = row;
      this.column = column;
    }

    @Override
    public int compareTo(APVertexID o) {
      if (o == null) {
        return 1;
      }

      if (!type.equals(o.type)) {
        return type.compareTo(o.type);
      }

      if (row != o.row) {
        return Long.compare(row, o.row);
      }

      if (column != o.column) {
        return Long.compare(column, o.column);
      }

      return 0;
    }

    @Override
    public void write(DataOutput dataOutput) throws IOException {
      dataOutput.writeInt(type.ordinal());
      dataOutput.writeLong(row);
      dataOutput.writeLong(column);
    }

    @Override
    public void readFields(DataInput dataInput) throws IOException {
      final int index = dataInput.readInt();
      System.err.println("Type index: " + index);
      type = VertexType.values()[index];
      row = dataInput.readLong();
      column = dataInput.readLong();
    }

    @Override
    public String toString() {
      return "(" + type + ", " + row + ", " + column + ")";
    }
  }

  public static class APMessage implements Writable {

    public APVertexID from;
    public double value;

    public APMessage(){};

    public APMessage(APVertexID from, double value) {
      this.from = from;
      this.value = value;
    }

    @Override
    public void write(DataOutput dataOutput) throws IOException {
      from.write(dataOutput);
      dataOutput.writeDouble(value);
    }

    @Override
    public void readFields(DataInput dataInput) throws IOException {
      from.readFields(dataInput);
      value = dataInput.readDouble();
    }
  }

  public static class APInputFormatter
      extends TextVertexValueInputFormat<APVertexID, DoubleWritable, FloatWritable> {

    private static final Pattern SEPARATOR = Pattern.compile("[\001\t ]");

    @Override
    public TextVertexValueReader createVertexValueReader(InputSplit split, TaskAttemptContext context) throws IOException {
      return new APInputReader();
    }

    public class APInputReader extends TextVertexValueReaderFromEachLineProcessed<String[]> {

      @Override
      protected String[] preprocessLine(Text line) throws IOException {
        return SEPARATOR.split(line.toString());
      }

      @Override
      protected APVertexID getId(String[] line) throws IOException {
        APVertexID id = new APVertexID(VertexType.VARIABLE,
            Long.valueOf(line[0]), Long.valueOf(line[1]));
        return id;
      }

      @Override
      protected DoubleWritable getValue(String[] line) throws IOException {
        return new DoubleWritable(Double.valueOf(line[2]));
      }
    }

  }

  public class MessageCollector implements CommunicationAdapter<APVertexID> {
    @Override
    public void send(double value, APVertexID sender, APVertexID recipient) {
      System.err.println(sender + " -> " + recipient + " : " + value);
      AffinityPropagation.this.sendMessage(recipient, new APMessage(sender, value));
    }
  }

  public static class MasterComputation extends DefaultMasterCompute {

    @Override
    public void initialize() throws InstantiationException, IllegalAccessException {
      super.initialize();

      registerPersistentAggregator("nRows", LongMaxAggregator.class);
      registerPersistentAggregator("nColumns", LongMaxAggregator.class);
    }
  }

}
