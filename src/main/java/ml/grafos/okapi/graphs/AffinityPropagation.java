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

import com.google.common.collect.ComparisonChain;
import es.csic.iiia.bms.CommunicationAdapter;
import es.csic.iiia.bms.Factor;
import es.csic.iiia.bms.MaxOperator;
import es.csic.iiia.bms.Maximize;
import es.csic.iiia.bms.factors.ConditionedDeactivationFactor;
import es.csic.iiia.bms.factors.SelectorFactor;
import es.csic.iiia.bms.factors.WeightingFactor;
import ml.grafos.okapi.common.data.*;
import ml.grafos.okapi.common.data.MapWritable;
import org.apache.giraph.aggregators.BasicAggregator;
import org.apache.giraph.edge.Edge;
import org.apache.giraph.graph.BasicComputation;
import org.apache.giraph.graph.Vertex;
import org.apache.giraph.io.EdgeReader;
import org.apache.giraph.io.formats.IdWithValueTextOutputFormat;
import org.apache.giraph.io.formats.TextEdgeInputFormat;
import org.apache.giraph.io.formats.TextVertexValueInputFormat;
import org.apache.giraph.master.DefaultMasterCompute;
import org.apache.giraph.utils.ArrayListWritable;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Affinity Propagation is a clustering algorithm.
 *
 * <p>The number of clusters is not received as an input, but computed by the
 * algorithm according to the distances between points and the preference
 * of each node to be an <i>exemplar</i> (the "leader" of a cluster).
 *
 * <p>You can find a detailed description of the algorithm in the affinity propagation
 * <a href="http://genes.toronto.edu/index.php?q=affinity%20propagation">website</a>.
 *
 * @author Marc Pujol-Gonzalez <mpujol@iiia.csic.es>
 * @author Toni Penya-Alba <tonipenya@iiia.csic.es>
 */
public class AffinityPropagation
    extends BasicComputation<AffinityPropagation.APVertexID,
    AffinityPropagation.APVertexValue, DoubleWritable, AffinityPropagation.APMessage> {
  private static MaxOperator MAX_OPERATOR = new Maximize();

  private static Logger logger = LoggerFactory.getLogger(AffinityPropagation.class);

  /**
   * Maximum number of iterations.
   */
  public static final String MAX_ITERATIONS = "iterations";
  public static int MAX_ITERATIONS_DEFAULT = 15;
  /**
   * Damping factor.
   */
  public static final String DAMPING = "damping";
  public static float DAMPING_DEFAULT = 0.9f;

  @Override
  public void compute(Vertex<APVertexID, APVertexValue, DoubleWritable> vertex,
                      Iterable<APMessage> messages) throws IOException {
    logger.trace("vertex " + vertex.getId() + ", superstep " + getSuperstep());
    final int maxIter = getContext().getConfiguration().getInt(MAX_ITERATIONS, MAX_ITERATIONS_DEFAULT);
    // Phases of the algorithm
    if (getSuperstep() == 0) {
      initRows(vertex);
    } else if (getSuperstep() == 1) {
      initColumns(vertex, messages);
    } else if (getSuperstep() < maxIter) {
      computeBMSIteration(vertex, messages);
    } else if (getSuperstep() == maxIter) {
      computeExemplars(vertex, messages);
    } else {
      computeClusters(vertex);
    }
  }

  private void initRows(Vertex<APVertexID, APVertexValue, DoubleWritable> vertex) throws IOException {
    final boolean isVertexFormat = getConf().getVertexInputFormatClass() != null;
    if (isVertexFormat) {
      initRowsFromVertexInput(vertex);
    } else {
      initRowsFromEdgeInput(vertex);
    }
  }

  private void initRowsFromVertexInput(Vertex<APVertexID, APVertexValue, DoubleWritable> vertex) {
    final long nVertices = getTotalNumVertices();
    for (int i = 1; i <= nVertices; i++) {
      APVertexID neighbor = new APVertexID(APVertexType.COLUMN, i);
      vertex.getValue().lastMessages.put(neighbor, new DoubleWritable(0));
      sendMessage(neighbor, new APMessage(vertex.getId(), 0));
    }
  }

  private void initRowsFromEdgeInput(Vertex<APVertexID, APVertexValue, DoubleWritable> vertex) throws IOException {
    for (Edge<APVertexID, DoubleWritable> edge : vertex.getEdges()) {
      APVertexID neighbor = new APVertexID(edge.getTargetVertexId());
      DoubleWritable weight = new DoubleWritable(edge.getValue().get());
      vertex.getValue().weights.put(neighbor, weight);
      vertex.getValue().lastMessages.put(neighbor, new DoubleWritable(0));
      sendMessage(neighbor, new APMessage(vertex.getId(), 0));
      removeEdgesRequest(vertex.getId(), neighbor);
    }
  }

  private void initColumns(Vertex<APVertexID, APVertexValue, DoubleWritable> vertex, Iterable<APMessage> messages) {
    if (vertex.getId().type == APVertexType.ROW) {
      return;
    }

    for (APMessage message : messages) {
      APVertexID neighbor = new APVertexID(message.from);
      vertex.getValue().lastMessages.put(neighbor, new DoubleWritable(0));
      sendMessage(neighbor, new APMessage(vertex.getId(), 0));
    }
  }

  private void computeBMSIteration(Vertex<APVertexID, APVertexValue, DoubleWritable> vertex,
                                   Iterable<APMessage> messages) throws IOException {
    final APVertexID id = vertex.getId();

    // Build a factor of the required type
    Factor<APVertexID> factor;
    switch (id.type) {

      case COLUMN:
        ConditionedDeactivationFactor<APVertexID> node2 = new ConditionedDeactivationFactor<APVertexID>();
        node2.setExemplar(new APVertexID(APVertexType.ROW, id.index));
        factor = node2;

        for (Writable key : vertex.getValue().lastMessages.keySet()) {
          APVertexID rowId = (APVertexID) key;
          logger.trace("{} adds neighbor {}", id, rowId);
          node2.addNeighbor(rowId);
        }
        break;

      case ROW:
        final MapWritable value = vertex.getValue().weights;

        SelectorFactor<APVertexID> selector = new SelectorFactor<APVertexID>();
        WeightingFactor<APVertexID> weights = new WeightingFactor<APVertexID>(selector);
        for (Writable key : vertex.getValue().lastMessages.keySet()) {
          APVertexID varId = (APVertexID) key;
          weights.addNeighbor(varId);
          weights.setPotential(varId, ((DoubleWritable) value.get(varId)).get());
        }
        factor = weights;
        break;

      default:
        throw new IllegalStateException("Unrecognized node type " + id.type);
    }

    // Initialize it with proper values
    MessageRelayer collector = new MessageRelayer(vertex.getValue().lastMessages);
    factor.setCommunicationAdapter(collector);
    factor.setIdentity(id);
    factor.setMaxOperator(MAX_OPERATOR);

    // Receive messages and compute
    for (APMessage message : messages) {
      factor.receive(message.value, message.from);
    }
    factor.run();
  }

  private void computeExemplars(Vertex<APVertexID, APVertexValue, DoubleWritable> vertex,
                                Iterable<APMessage> messages) throws IOException {
    final APVertexID id = vertex.getId();
    // Exemplars are auto-elected among variables
    if (id.type != APVertexType.COLUMN) {
      return;
    }

    // But only by those variables on the diagonal of the matrix
    for (APMessage message : messages) {
      if (message.from.index == id.index) {
        double lastMessageValue = ((DoubleWritable) vertex.getValue().lastMessages.get(message.from)).get();
        double belief = message.value + lastMessageValue;
        if (belief >= 0) {
          LongArrayListWritable exemplars = new LongArrayListWritable();
          exemplars.add(new LongWritable(id.index));
          aggregate("exemplars", exemplars);
          logger.trace("Point " + id.index + " decides to become an exemplar with value " + belief + ".");
        } else {
          logger.trace("Point " + id.index + " does not want to be an exemplar with value " + belief + ".");
        }

      }
    }

    vertex.voteToHalt();
  }

  private void computeClusters(Vertex<APVertexID, APVertexValue, DoubleWritable> vertex) throws IOException {
    APVertexID id = vertex.getId();
    if (id.type != APVertexType.ROW) {
      return;
    }

    final LongArrayListWritable exemplars = getAggregatedValue("exemplars");
    if (exemplars.contains(new LongWritable(id.index))) {
      logger.trace("Point {} is an exemplar.", id.index);
      vertex.getValue().exemplar = new LongWritable(id.index);
      vertex.voteToHalt();
      return;
    }

    long bestExemplar = -1;
    double maxValue = Double.NEGATIVE_INFINITY;
    MapWritable values = vertex.getValue().weights;
    for (LongWritable exemplarWritable : exemplars) {
      final long exemplar = exemplarWritable.get();
      final APVertexID neighbor = new APVertexID(APVertexType.COLUMN, exemplar);
      if (!values.containsKey(neighbor)) {
        continue;
      }

      final double value =  ((DoubleWritable) values.get(neighbor)).get();
      if (value > maxValue) {
        maxValue = value;
        bestExemplar = exemplar;
      }
    }

    logger.trace("Point " + id.index + " decides to follow " + bestExemplar + ".");
    vertex.getValue().exemplar = new LongWritable(bestExemplar);
    vertex.voteToHalt();
  }

  public static enum APVertexType {
    COLUMN, ROW
  }

  public static class APVertexID implements WritableComparable<APVertexID> {

    public APVertexType type = APVertexType.ROW;
    public long index = 0;

    public APVertexID() {
    }

    public APVertexID(APVertexID orig) {
      this(orig.type, orig.index);
    }

    public APVertexID(APVertexType type, long index) {
      this.type = type;
      this.index = index;
    }

    @Override
    public void write(DataOutput dataOutput) throws IOException {
      dataOutput.writeInt(type.ordinal());
      dataOutput.writeLong(index);
    }

    @Override
    public void readFields(DataInput dataInput) throws IOException {
      final int index = dataInput.readInt();
      type = APVertexType.values()[index];
      this.index = dataInput.readLong();
    }

    @Override
    public int compareTo(APVertexID that) {
      return ComparisonChain.start()
          .compare(this.type, that.type)
          .compare(this.index, that.index)
          .result();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      APVertexID that = (APVertexID) o;
      return index == that.index && type == that.type;
    }

    @Override
    public int hashCode() {
      int result = type.hashCode();
      result = 31 * result + (int) (index ^ (index >>> 32));
      return result;
    }

    @Override
    public String toString() {
      return "(" + type + ", " + index + ")";
    }
  }

  public static class APVertexValue implements Writable {
    public LongWritable exemplar;
    public MapWritable weights;
    public MapWritable lastMessages;

    public APVertexValue() {
      exemplar = new LongWritable();
      weights = new MapWritable();
      lastMessages = new MapWritable();
    }

    @Override
    public void write(DataOutput dataOutput) throws IOException {
      exemplar.write(dataOutput);
      weights.write(dataOutput);
      lastMessages.write(dataOutput);
    }

    @Override
    public void readFields(DataInput dataInput) throws IOException {
      exemplar.readFields(dataInput);
      weights.readFields(dataInput);
      lastMessages.readFields(dataInput);
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
    private MapWritable lastMessages;
    final float damping = getContext().getConfiguration().getFloat(DAMPING, DAMPING_DEFAULT);

    public MessageRelayer(MapWritable lastMessages) {
      this.lastMessages = lastMessages;
    }

    @Override
    public void send(double value, APVertexID sender, APVertexID recipient) {
      if (lastMessages.containsKey(recipient)) {
        final double lastMessage = ((DoubleWritable) lastMessages.get(recipient)).get();
        value = damping * lastMessage + (1-damping) * value;
      }
      logger.trace(sender + " -> " + recipient + " : " + value);
      AffinityPropagation.this.sendMessage(recipient, new APMessage(sender, value));
      lastMessages.put(recipient, new DoubleWritable(value));
    }
  }

  public static class MasterComputation extends DefaultMasterCompute {

    @Override
    public void initialize() throws InstantiationException, IllegalAccessException {
      super.initialize();
      registerPersistentAggregator("exemplars", ExemplarAggregator.class);
    }

  }

  public static class ExemplarAggregator extends BasicAggregator<LongArrayListWritable> {

    @Override
    public void aggregate(LongArrayListWritable value) {
      getAggregatedValue().addAll(value);
    }

    @Override
    public LongArrayListWritable createInitialValue() {
      return new LongArrayListWritable();
    }
  }

  /**
   * Vertex input formatter for Affinity Propagation problems.
   * <p/>
   * The input format consists of an entry for each of the data points to cluster.
   * The first element of the entry is an integer value encoding the data point
   * index (id). Subsequent elements in the entry are double values encoding the
   * similarities between the data point of the current entry and the rest of
   * data points in the problem.
   * <p/>
   * Example:<br/>
   * 1 1 1 5
   * 2 1 1 3
   * 3 5 3 1
   * <p/>
   * Encodes a problem in which data point "1" has similarity 1 with itself,
   * 1 with point "2" and 5 with point "3". In a similar manner, points "2",
   * and "3" have similarities of [1, 1, 3] and [5, 3, 1] respectively with
   * points "1", "2", and "3".
   *
   * @author Marc Pujol-Gonzalez <mpujol@iiia.csic.es>
   * @author Toni Penya-Alba <tonipenya@iiia.csic.es>
   */
  public static class APVertexInputFormatter
      extends TextVertexValueInputFormat<APVertexID, APVertexValue, DoubleWritable> {

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
        return new APVertexID(APVertexType.ROW,
            Long.valueOf(line[0]));
      }

      @Override
      protected APVertexValue getValue(String[] line) throws IOException {
        APVertexValue value = new APVertexValue();
        for (int i = 1; i < line.length; i++) {
          APVertexID neighId = new APVertexID(APVertexType.COLUMN, i);
          value.weights.put(neighId, new DoubleWritable(Double.valueOf(line[i])));
        }
        return value;
      }
    }
  }

  /**
   * Edge input formatter for Affinity Propagation problems.
   * <p/>
   * The input format consists of an entry for each pair of points. The first
   * element of the entry denotes the id of the first point. Similarly, the
   * second element denotes the id of the second point. Finally, the third
   * element contains a double value encoding the similarity between the first
   * and second points.
   * <p/>
   * Example:<br/>
   * 1 1 1
   * 1 2 1
   * 1 3 5
   * 2 1 1
   * 2 2 1
   * 2 3 3
   * 3 1 5
   * 3 2 3
   * 3 3 1
   * <p/>
   * Encodes a problem in which data point "1" has similarity 1 with itself,
   * 1 with point "2" and 5 with point "3". In a similar manner, points "2",
   * and "3" have similarities of [1, 1, 3] and [5, 3, 1] respectively with
   * points "1", "2", and "3".
   *
   * @author Marc Pujol-Gonzalez <mpujol@iiia.csic.es>
   * @author Toni Penya-Alba <tonipenya@iiia.csic.es>
   */
  public static class APEdgeInputFormatter extends TextEdgeInputFormat<APVertexID, DoubleWritable> {

    private static final Pattern SEPARATOR = Pattern.compile("[\001\t ]");

    @Override
    public EdgeReader<APVertexID, DoubleWritable> createEdgeReader(InputSplit split, TaskAttemptContext context) throws IOException {
      return new APEdgeInputReader();
    }

    public class APEdgeInputReader extends TextEdgeReaderFromEachLineProcessed<String []> {

      @Override
      protected String[] preprocessLine(Text line) throws IOException {
        return SEPARATOR.split(line.toString());
      }

      @Override
      protected APVertexID getTargetVertexId(String[] line) throws IOException {
        return new APVertexID(APVertexType.COLUMN, Long.valueOf(line[1]));
      }

      @Override
      protected APVertexID getSourceVertexId(String[] line) throws IOException {
        return new APVertexID(APVertexType.ROW, Long.valueOf(line[0]));
      }

      @Override
      protected DoubleWritable getValue(String[] line) throws IOException {
        return new DoubleWritable(Double.valueOf(line[2]));
      }
    }
  }

  /**
   * Output Formatter for Affinity Propagation problems.
   * <p/>
   * The output format consists of an entry for each of the data points to cluster.
   * The first element of the entry is a integer value encoding the data point
   * index (id), whereas the second value encodes the exemplar id chosen for
   * that point.
   * <p/>
   * Example:<br/>
   * 1 3
   * 2 3
   * 3 3
   * <p/>
   * Encodes a solution in which data points "1", "2", and "3" choose point "3"
   * as an exemplar.
   *
   * @author Marc Pujol-Gonzalez <mpujol@iiia.csic.es>
   * @author Toni Penya-Alba <tonipenya@iiia.csic.es>
   */
  @SuppressWarnings("rawtypes")
  public static class APOutputFormat
      extends IdWithValueTextOutputFormat<APVertexID,APVertexValue, DoubleWritable> {

    /**
     * Specify the output delimiter
     */
    public static final String LINE_TOKENIZE_VALUE = "output.delimiter";
    /**
     * Default output delimiter
     */
    public static final String LINE_TOKENIZE_VALUE_DEFAULT = "\t";

    @Override
    public TextVertexWriter createVertexWriter(TaskAttemptContext context) {
      return new IdWithValueVertexWriter();
    }

    protected class IdWithValueVertexWriter extends TextVertexWriterToEachLine {
      /**
       * Saved delimiter
       */
      private String delimiter;

      @Override
      public void initialize(TaskAttemptContext context) throws IOException,
          InterruptedException {
        super.initialize(context);
        delimiter = getConf().get(
            LINE_TOKENIZE_VALUE, LINE_TOKENIZE_VALUE_DEFAULT);
      }

      @Override
      protected Text convertVertexToLine(Vertex<APVertexID,
          APVertexValue, DoubleWritable> vertex)
          throws IOException {

        if (vertex.getId().type != APVertexType.ROW) {
          return null;
        }

        return new Text(String.valueOf(vertex.getId().index)
            + delimiter + Long.toString(vertex.getValue().exemplar.get()));
      }
    }
  }
}
