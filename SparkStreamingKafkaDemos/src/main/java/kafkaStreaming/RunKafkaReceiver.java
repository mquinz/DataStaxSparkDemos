package kafkaStreaming;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;


import com.datastax.driver.core.Session;
import com.datastax.spark.connector.cql.CassandraConnector;
import org.apache.spark.streaming.StreamingContext;
import org.apache.spark.streaming.api.java.*;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import scala.Tuple2;

import org.apache.spark.SparkConf;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.kafka.KafkaUtils;

import simpleSpark.SparkConfSetup;

import static com.datastax.spark.connector.japi.CassandraJavaUtil.mapToRow;
import static com.datastax.spark.connector.japi.CassandraStreamingJavaUtil.javaFunctions;

public class RunKafkaReceiver {

   public static Duration getDurationsSeconds(int seconds) {
        return new Duration(seconds * 1000);
    }

    public static JavaStreamingContext getJavaStreamingContext(Duration batchDuration) {
        StreamingContext streamingContext = new StreamingContext(SparkConfSetup.getSparkConf(), batchDuration);
        return new JavaStreamingContext(streamingContext);
    }

    public static void main(String[] args) {

        if (args.length < 1) {
            System.out.println("need to pass zookeeper host at command line, i.e.  localhost:9092");
            System.exit(-1);
        }

        String zookeeperHost = args[0];
        System.out.println("starting listening on zookeeper = " + zookeeperHost);

        CassandraConnector connector = SparkConfSetup.getCassandraConnector();
        setupCassandraTables(connector);

        // Create the context with a 5 second batch size
        JavaStreamingContext javaStreamingContext = getJavaStreamingContext(getDurationsSeconds(5));

        int numThreads = 1;
        Map<String, Integer> topicMap = new HashMap<String, Integer>();
        topicMap.put(StreamingProperties.kafkaTopic, numThreads);

/*
        int numThreads = Integer.parseInt(args[3]);
        String[] topics = args[2].split(",");
        for (String topic: topics) {
            topicMap.put(topic, numThreads);
        }
*/

        JavaPairReceiverInputDStream<String, String> messages =
                KafkaUtils.createStream(javaStreamingContext, zookeeperHost, StreamingProperties.kafkaConsumerGroup, topicMap);

        System.out.println("Setup kafka streaming on topic: " + StreamingProperties.kafkaTopic);

        JavaDStream<String> mappedMsgs = messages.map(nxtMsg -> nxtMsg._2());

        // process the stream here
         basicWordsMapAndSave(mappedMsgs);


        javaStreamingContext.start();
        javaStreamingContext.awaitTermination();
    }


    private static void basicWordsMapAndSave(JavaDStream<String> lines) {
        JavaPairDStream<String, Integer> wordMap = lines.flatMap(word -> Arrays.asList(word.split("\\s+")))
                .mapToPair(word -> new Tuple2<>(word.toLowerCase(), 1))
                .reduceByKeyAndWindow((keyCount, wordCount) -> keyCount + wordCount, getDurationsSeconds(30), getDurationsSeconds(10));  // Reduce last 30 seconds of data, every 10 seconds


        JavaDStream<WordCount> wordCountStream = wordMap.map(wordCountPair -> new WordCount(wordCountPair._1(), wordCountPair._2(), new DateTime()));

        javaFunctions(wordCountStream)
                .writerBuilder("streamdemo", "wordcount", mapToRow(WordCount.class))
                .saveToCassandra();

    }



    private static void setupCassandraTables(CassandraConnector connector) {
        try (Session session = connector.openSession()) {
            session.execute("CREATE KEYSPACE IF NOT EXISTS streamdemo WITH REPLICATION = {'class': 'SimpleStrategy', 'replication_factor': 1 }");
            session.execute("DROP TABLE IF EXISTS streamdemo.wordcount;");
            session.execute("CREATE TABLE IF NOT EXISTS streamdemo.wordcount (timewindow TEXT, word TEXT, count INT, PRIMARY KEY (timewindow, word, count))");

            session.execute("DROP TABLE IF EXISTS streamdemo.word_analytics;");
            session.execute("CREATE TABLE IF NOT EXISTS streamdemo.word_analytics (series text, " +
                    "  timewindow timestamp, " +
                    "  quantities map<text, int>, " +
                    "  PRIMARY KEY ((series), timewindow) " +
                    ") WITH CLUSTERING ORDER BY (timewindow DESC)");
        }
    }

    //Format the date as the "Day of the Year" "hour of the day" and "minute of the hour" and "second of the minute" to bucket data for the current minute
    private static DateTimeFormatter fmt = DateTimeFormat.forPattern("DHms");

    public static class WordCount implements Serializable {
        private String word;
        private Integer count;
        private DateTime timewindow;

        public WordCount(String word, Integer count, DateTime timewindow) {
            this.word = word;
            this.count = count;
            this.timewindow = timewindow;
        }

        public String getWord() {
            return word;
        }

        public Integer getCount() {
            return count;
        }

        public String getTimewindow() {
            return timewindow.toString(fmt);
        }

        @Override
        public String toString() {
            return "WordCount{" +
                    "word='" + word + '\'' +
                    ", count=" + count +
                    ", timewindow=" + timewindow +
                    '}';
        }
    }


    public static class WordCountAnalysis implements Serializable {
        private String series;
        private DateTime timewindow;
        Map<String, Integer> quantities;

        public WordCountAnalysis(String series, DateTime timewindow, Map<String, Integer> quantities) {
            this.series = series;
            this.timewindow = timewindow;
            this.quantities = quantities;
        }

        public String getSeries() {
            return series;
        }

        public DateTime getTimewindow() {
            return timewindow;
        }

        public Map<String, Integer> getQuantities() {
            return quantities;
        }

        @Override
        public String toString() {
            return "WordCountAnalysis{" +
                    "series='" + series + '\'' +
                    ", timewindow=" + timewindow +
                    ", quantities=" + quantities +
                    '}';
        }
    }

}
