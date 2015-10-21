package com.mapr.pcapstream

import java.net.InetAddress

import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.apache.spark._

import org.apache.hadoop.io.{BytesWritable, NullWritable}
import org.apache.hadoop.mapred.FileInputFormat
import org.apache.hadoop.mapred.JobConf

import com.fasterxml.jackson.databind.{DeserializationFeature, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule

import com.mapr.sample.WholeFileInputFormat
import edu.gatech.sjpcap._

object PcapStream {
  case class FlowData(timestampMillis: Long, srcIP: InetAddress, dstIP: InetAddress, srcPort: Integer, dstPort: Integer, protocol: String, length: Integer, captureFilename: String)

  def main(args: Array[String]) {
    val inputPath = args(0)
    val outputPath = args(1)

    val conf = new SparkConf().setAppName("PCAP Flow Parser")
    val ssc = new StreamingContext(conf, Seconds(20))
    val sc = ssc.sparkContext
    val sqlContext = new org.apache.spark.sql.SQLContext(sc)
    import sqlContext.implicits._

    val input = inputPath
    val output = outputPath

    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // val jobConf = new JobConf(ssc.sparkContext.hadoopConfiguration)
    val jobConf = new JobConf(sc.hadoopConfiguration)
    jobConf.setJobName("PCAP Stream Processing")
    FileInputFormat.setInputPaths(jobConf, input)

    val pcapBytes = ssc.fileStream[NullWritable, BytesWritable, WholeFileInputFormat](input)

    //val pcapBytes = sc.newAPIHadoopRDD(jobConf, classOf[WholeFileInputFormat], classOf[NullWritable], classOf[BytesWritable])

    /* Can't cast UnionRDD to NewHadoopRDD, so there's no clean way to get the input filename. Will try to infer it externally with directories. */
    /*
    val hadoopRdd = pcapBytes.foreachRDD(rdd => rdd.asInstanceOf[NewHadoopRDD[NullWritable,BytesWritable]].mapPartitionsWithInputSplit { ( inputSplit, iterator) =>
      val file = inputSplit.asInstanceOf[FileSplit]
      iterator.map { tpl => (file.getPath.toString, tpl._2) }
    })
    */

    val packets = pcapBytes.flatMap {
        case (filename, packet) =>
          val pcapParser = new PcapParser()
          pcapParser.openFile(packet.getBytes)

          val pcapIterator = new PcapIterator(pcapParser)
          for (flowData <- pcapIterator.toList if flowData != None)
            yield (flowData.get)
    }

    packets.saveAsTextFiles(outputPath)

    ssc.start()
    ssc.awaitTermination()
  }

  class PcapIterator(pcapParser: PcapParser, filename: String = "") extends Iterator[Option[FlowData]] {
    private var _headerMap: Option[FlowData] = None

    def next() = {
        _headerMap
    }

    def hasNext: Boolean = {
      val packet = pcapParser.getPacket
      if (packet == Packet.EOF)
        _headerMap = None
      else
        _headerMap = extractFlowData(packet, Some(filename))
      packet != Packet.EOF
    }
  }

  def extractFlowData(packet: Packet, filename: Option[String] = Some("")): Option[FlowData] = {
    packet match {
      case t: TCPPacket => Some(new FlowData(t.timestamp, t.src_ip, t.dst_ip, t.src_port, t.dst_port, "TCP", t.data.length, filename.get))
      case u: UDPPacket => Some(new FlowData(u.timestamp, u.src_ip, u.dst_ip, u.src_port, u.dst_port, "UDP", u.data.length, filename.get))
      case _ => None
    }
  }

  def headerToJson(mapper: ObjectMapper, flowData: FlowData): String = {
    mapper.writeValueAsString(flowData)
  }
}
