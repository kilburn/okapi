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
import ml.grafos.okapi.common.data.DoubleArrayListWritable;
import ml.grafos.okapi.common.data.LongArrayListWritable;
import org.apache.giraph.aggregators.BasicAggregator;
import org.apache.giraph.aggregators.LongMaxAggregator;
import org.apache.giraph.graph.BasicComputation;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.io.formats.IdWithValueTextOutputFormat;
import org.apache.giraph.io.formats.TextVertexValueInputFormat;
import org.apache.giraph.master.DefaultMasterCompute;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
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
    DoubleArrayListWritable, FloatWritable, AffinityPropagation.APMessage> {

  private static MaxOperator MAX_OPERATOR = new Maximize();

  private static Logger logger = Logger.getLogger(AffinityPropagation.class);

  /** Maximum number of iterations. */
  public static final String MAX_ITERATIONS = "iterations";
  public static int MAX_ITERATIONS_DEFAULT = 15;

  @Override
  public void compute(Vertex<APVertexID, DoubleArrayListWritable, FloatWritable> vertex,
                      Iterable<APMessage> messages) throws IOException {
    logger.trace("vertex " + vertex.getId() + ", superstep " + getSuperstep());
    final int maxIter = getContext().getConfiguration().getInt(MAX_ITERATIONS, MAX_ITERATIONS_DEFAULT);
    // Phases of the algorithm
    if (getSuperstep() == 0) {
      computeRowsColumns(vertex, messages);
    } else if (getSuperstep() < maxIter) {
      computeBMSIteration(vertex, messages);
    } else if (getSuperstep() == maxIter) {
      computeLeaders(vertex, messages);
    } else {
      computeClusters(vertex, messages);
    }

  }

  private void computeRowsColumns(Vertex<APVertexID, DoubleArrayListWritable, FloatWritable> vertex,
                                  Iterable<APMessage> messages) throws IOException {
    final APVertexID id = vertex.getId();
    aggregate("nRows", new LongWritable(id.row));
    aggregate("nColumns", new LongWritable(id.column));
  }

  private void computeBMSIteration(Vertex<APVertexID, DoubleArrayListWritable, FloatWritable> vertex,
                                   Iterable<APMessage> messages) throws IOException {
    final APVertexID id = vertex.getId();

    LongWritable aggregatedRows = getAggregatedValue("nRows");
    final long nRows = aggregatedRows.get();
    LongWritable aggregatedColumns = getAggregatedValue("nRows");
    final long nColumns = aggregatedColumns.get();
    if (nRows != nColumns) {
      throw new IllegalStateException("The input must form a square matrix, but we got " +
          nRows + " rows and " + nColumns + "columns.");
    }

    if (getSuperstep() == 1) {
      logger.trace("Number of rows: " + nRows);
      logger.trace("Number of columns: " + nColumns);
    }

    // Build a factor of the required type
    Factor<APVertexID> factor;
    switch (id.type) {

      case CONSISTENCY:
        ConditionedDeactivationFactor<APVertexID> node2 = new ConditionedDeactivationFactor<APVertexID>();
        node2.setExemplar(new APVertexID(APVertexType.SELECTOR, id.column, 0));
        factor = node2;

        for (int row = 1; row <= nRows; row++) {
          APVertexID varId = new APVertexID(APVertexType.SELECTOR, row, 0);
          node2.addNeighbor(varId);
        }

        break;

      case SELECTOR:
        final DoubleArrayListWritable value = vertex.getValue();
        SelectorFactor<APVertexID> selector = new SelectorFactor<APVertexID>();
        WeightingFactor<APVertexID> weights = new WeightingFactor<APVertexID>(selector);
        for (int column = 1; column <= nColumns; column++) {
          APVertexID varId = new APVertexID(APVertexType.CONSISTENCY, 0, column);
          weights.addNeighbor(varId);
          weights.setPotential(varId, value.get(column-1).get());
        }
        factor = weights;
        break;

      default:
        throw new IllegalStateException("Unrecognized node type " + id.type);
    }

    // Initialize it with proper values
    MessageRelayer collector = new MessageRelayer();
    factor.setCommunicationAdapter(collector);
    factor.setIdentity(id);
    factor.setMaxOperator(MAX_OPERATOR);

    // Receive messages and compute
    for (APMessage message : messages) {
      logger.trace(message);
      factor.receive(message.value, message.from);
    }
    factor.run();
  }

  private void computeLeaders(Vertex<APVertexID, DoubleArrayListWritable, FloatWritable> vertex,
                      Iterable<APMessage> messages) throws IOException {
    final APVertexID id = vertex.getId();

    // Leaders are auto-elected among variables
    if (!(id.type == APVertexType.CONSISTENCY)) {
      return;
    }

    // COPYPASTA FEST
    LongWritable aggregatedRows = getAggregatedValue("nRows");
    final long nRows = aggregatedRows.get();
    LongWritable aggregatedColumns = getAggregatedValue("nRows");
    final long nColumns = aggregatedColumns.get();
    if (nRows != nColumns) {
      throw new IllegalStateException("The input must form a square matrix, but we got " +
          nRows + " rows and " + nColumns + "columns.");
    }

    // Build a factor of the required type
    Factor<APVertexID> factor;

    ConditionedDeactivationFactor<APVertexID> node2 = new ConditionedDeactivationFactor<APVertexID>();
    node2.setExemplar(new APVertexID(APVertexType.SELECTOR, id.column, 0));
    factor = node2;

    for (int row = 1; row <= nRows; row++) {
      APVertexID varId = new APVertexID(APVertexType.SELECTOR, row, 0);
      node2.addNeighbor(varId);
    }

    // Initialize it with proper values
    CaptureMessageGoingToRow capturer = new CaptureMessageGoingToRow(id.column);
    factor.setCommunicationAdapter(capturer);
    factor.setIdentity(id);
    factor.setMaxOperator(MAX_OPERATOR);

    // Receive messages and compute
    for (APMessage message : messages) {
      logger.trace(message);
      factor.receive(message.value, message.from);
    }
    factor.run();


    // But only by those variables on the diagonal of the matrix
    for (APMessage message : messages) {
      if (message.from.row == id.column) {
        double belief = message.value + capturer.capturedMessage;
        if (belief >= 0) {
          LongArrayListWritable leaders = new LongArrayListWritable();
          leaders.add(new LongWritable(id.column));
          aggregate("leaders", leaders);
          logger.trace("Point " + id.column + " decides to become a leader with value " + belief + ".");
        } else {
          logger.trace("Point " + id.column + " does not want to be a leader with value " + belief + ".");
        }

      }
    }

    vertex.voteToHalt();

  }

  private void computeClusters(Vertex<APVertexID, DoubleArrayListWritable, FloatWritable> vertex,
                      Iterable<APMessage> messages) throws IOException {

    APVertexID id = vertex.getId();
    if (id.type != APVertexType.SELECTOR) {
      return;
    }

    final LongArrayListWritable ls = getAggregatedValue("leaders");
    DoubleArrayListWritable values = vertex.getValue();
    double maxValue = Double.NEGATIVE_INFINITY;
    long bestLeader = -1;
    for (LongWritable l : ls) {
      final long leader = l.get();

      if (leader == id.row) {
        logger.trace("Point " + id.row + " is a leader.");
        vertex.getValue().clear();
        vertex.getValue().add(new DoubleWritable(id.row));
        vertex.voteToHalt();
        return;
      }

      final double value = values.get((int)(leader-1)).get();
      if (value > maxValue) {
        maxValue = value;
        bestLeader = leader;
      }
    }

    logger.trace("Point " + id.row + " decides to follow " + bestLeader + ".");
    vertex.getValue().clear();
    vertex.getValue().add(new DoubleWritable(bestLeader));
    vertex.voteToHalt();
  }

  public static enum APVertexType {
    CONSISTENCY, SELECTOR
  }

  public static class APVertexID implements WritableComparable<APVertexID> {

    public APVertexType type = APVertexType.SELECTOR;
    public long row = 0;
    public long column = 0;

    public APVertexID() {}

    public APVertexID(APVertexType type, long row, long column) {
      this.type = type;
      this.row = row;
      this.column = column;
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
      type = APVertexType.values()[index];
      row = dataInput.readLong();
      column = dataInput.readLong();
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
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      APVertexID that = (APVertexID) o;

      if (column != that.column) return false;
      if (row != that.row) return false;
      if (type != that.type) return false;

      return true;
    }

    @Override
    public int hashCode() {
      int result = type.hashCode();
      result = 31 * result + (int) (row ^ (row >>> 32));
      result = 31 * result + (int) (column ^ (column >>> 32));
      return result;
    }

    @Override
    public String toString() {
      return "(" + type + ", " + row + ", " + column + ")";
    }
  }

  public static class APMessage implements Writable {

    public APVertexID from;
    public double value;

    public APMessage() {
      from = new APVertexID();
    }

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

    @Override
    public String toString() {
      return "APMessage{from=" + from + ", value=" + value + '}';
    }
  }

  public class MessageRelayer implements CommunicationAdapter<APVertexID> {
    @Override
    public void send(double value, APVertexID sender, APVertexID recipient) {
      logger.trace(sender + " -> " + recipient + " : " + value);
      AffinityPropagation.this.sendMessage(recipient, new APMessage(sender, value));
    }
  }

  public class CaptureMessageGoingToRow implements CommunicationAdapter<APVertexID> {

    public double capturedMessage;

    private final long row;

    public CaptureMessageGoingToRow(long row) {
      this.row = row;
    }

    @Override
    public void send(double value, APVertexID sender, APVertexID recipient) {
      if (recipient.row == row) {
        capturedMessage = value;
      }
    }
  }

  public static class MasterComputation extends DefaultMasterCompute {

    @Override
    public void initialize() throws InstantiationException, IllegalAccessException {
      super.initialize();

      registerPersistentAggregator("nRows", LongMaxAggregator.class);
      registerPersistentAggregator("nColumns", LongMaxAggregator.class);
      registerPersistentAggregator("leaders", LeaderAggregator.class);
    }

  }

  public static class LeaderAggregator extends BasicAggregator<LongArrayListWritable> {
    @Override
    public void aggregate(LongArrayListWritable value) {
      getAggregatedValue().addAll(value);
    }

    @Override
    public LongArrayListWritable createInitialValue() {
      return new LongArrayListWritable();
    }
  }

  public static class APInputFormatter
      extends TextVertexValueInputFormat<APVertexID, DoubleArrayListWritable, FloatWritable> {

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
        return new APVertexID(APVertexType.SELECTOR,
            Long.valueOf(line[0]), 0);
      }

      @Override
      protected DoubleArrayListWritable getValue(String[] line) throws IOException {
        DoubleArrayListWritable value = new DoubleArrayListWritable();
        for (int i = 1; i < line.length; i++) {
          value.add(new DoubleWritable(Double.valueOf(line[i])));
        }
        return value;
      }
    }

  }

  @SuppressWarnings("rawtypes")
  public static class APOutputFormat
      extends IdWithValueTextOutputFormat<APVertexID,
      DoubleArrayListWritable, NullWritable> {

    /** Specify the output delimiter */
    public static final String LINE_TOKENIZE_VALUE = "output.delimiter";
    /** Default output delimiter */
    public static final String LINE_TOKENIZE_VALUE_DEFAULT = "\t";
    /** Reverse id and value order? */
    public static final String REVERSE_ID_AND_VALUE = "reverse.id.and.value";
    /** Default is to not reverse id and value order. */
    public static final boolean REVERSE_ID_AND_VALUE_DEFAULT = false;

    @Override
    public TextVertexWriter createVertexWriter(TaskAttemptContext context) {
      return new IdWithValueVertexWriter();
    }

    protected class IdWithValueVertexWriter extends TextVertexWriterToEachLine {
      /** Saved delimiter */
      private String delimiter;
      /** Cached reserve option */
      private boolean reverseOutput;

      @Override
      public void initialize(TaskAttemptContext context) throws IOException,
          InterruptedException {
        super.initialize(context);
        delimiter = getConf().get(
            LINE_TOKENIZE_VALUE, LINE_TOKENIZE_VALUE_DEFAULT);
        reverseOutput = getConf().getBoolean(
            REVERSE_ID_AND_VALUE, REVERSE_ID_AND_VALUE_DEFAULT);
      }

      @Override
      protected Text convertVertexToLine(Vertex<APVertexID,
          DoubleArrayListWritable, NullWritable> vertex)
          throws IOException {

        if (vertex.getId().type != APVertexType.SELECTOR) {
          return null;
        }

        StringBuilder str = new StringBuilder();
        if (reverseOutput) {
          str.append(vertex.getValue().toString());
          str.append(delimiter);
          str.append(Long.toString(vertex.getId().row));
        } else {
          str.append(vertex.getId().toString());
          str.append(delimiter);
          str.append(Double.toString(vertex.getValue().get(0).get()));
        }
        return new Text(str.toString());
      }
    }
  }


}
