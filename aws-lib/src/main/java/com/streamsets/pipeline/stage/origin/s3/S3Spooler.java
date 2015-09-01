/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stage.origin.s3;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.codahale.metrics.Meter;
import com.google.common.base.Preconditions;
import com.streamsets.pipeline.api.Source;
import com.streamsets.pipeline.config.PostProcessingOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class S3Spooler {

  private static final Logger LOG = LoggerFactory.getLogger(S3Spooler.class);

  private static final int MAX_SPOOL_SIZE = 1000;
  private static final int SPOOLER_QUEUE_SIZE = 100;

  private final Source.Context context;
  private final S3ConfigBean s3ConfigBean;
  private final AmazonS3Client s3Client;
  private PathMatcher pathMatcher;

  public S3Spooler(Source.Context context, S3ConfigBean s3ConfigBean) {
    this.context = context;
    this.s3ConfigBean = s3ConfigBean;
    this.s3Client = s3ConfigBean.s3Config.getS3Client();
  }

  private S3ObjectSummary currentObject;
  private ArrayBlockingQueue<S3ObjectSummary> objectQueue;
  private Meter spoolQueueMeter;

  public void init() {
    try {
      objectQueue = new ArrayBlockingQueue<>(SPOOLER_QUEUE_SIZE);
      spoolQueueMeter = context.createMeter("spoolQueue");

      pathMatcher = createPathMatcher(s3ConfigBean.s3FileConfig.filePattern);

    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  public void destroy() {
    if(objectQueue != null) {
      objectQueue.clear();
      objectQueue = null;
    }
  }

  S3ObjectSummary findAndQueueObjects(AmazonS3Source.S3Offset s3offset, boolean checkCurrent)
    throws AmazonClientException {
    List<S3ObjectSummary> s3ObjectSummaries = AmazonS3Util.listObjectsChronologically(
      s3Client, s3ConfigBean, pathMatcher, s3offset, objectQueue.remainingCapacity());
    for (S3ObjectSummary objectSummary : s3ObjectSummaries) {
      addObjectToQueue(objectSummary, checkCurrent);
    }
    spoolQueueMeter.mark(objectQueue.size());
    LOG.debug("Found '{}' files", objectQueue.size());
    return (s3ObjectSummaries.isEmpty()) ? null : s3ObjectSummaries.get(s3ObjectSummaries.size() - 1);
  }

  void addObjectToQueue(S3ObjectSummary objectSummary, boolean checkCurrent) {
    Preconditions.checkNotNull(objectSummary, "file cannot be null");
    if (checkCurrent) {
      Preconditions.checkState(currentObject == null ||
        currentObject.getLastModified().compareTo(objectSummary.getLastModified()) < 0);
    }
    if (!objectQueue.contains(objectSummary)) {
      if (objectQueue.size() >= MAX_SPOOL_SIZE) {
        LOG.warn("Exceeded '{}' of queued files", objectQueue.size());
      }
      objectQueue.add(objectSummary);
      spoolQueueMeter.mark(objectQueue.size());
    } else {
      LOG.warn("Object '{}' already in queue, ignoring", objectSummary.getKey());
    }
  }

  public S3ObjectSummary poolForObject(AmazonS3Source.S3Offset s3Offset, long wait, TimeUnit timeUnit)
    throws InterruptedException, AmazonClientException {
    Preconditions.checkArgument(wait >= 0, "wait must be zero or greater");
    Preconditions.checkNotNull(timeUnit, "timeUnit cannot be null");

    if(objectQueue.size() == 0) {
      findAndQueueObjects(s3Offset, false);
    }

    S3ObjectSummary next = null;
    try {
      LOG.debug("Polling for file, waiting '{}' ms", TimeUnit.MILLISECONDS.convert(wait, timeUnit));
      next = objectQueue.poll(wait, timeUnit);
    } catch (InterruptedException ex) {
      next = null;
    } finally {
      LOG.debug("Polling for file returned '{}'", next);
      if (next != null) {
        currentObject = next;
      }
    }
    return next;
  }

  void postProcessOrErrorHandle(String postProcessObjectKey, PostProcessingOptions postProcessing, String postProcessBucket,
                                String postProcessFolder, S3ArchivingOption archivingOption) {
    switch (postProcessing) {
      case NONE:
        break;
      case DELETE:
        delete(postProcessObjectKey);
        break;
      case ARCHIVE:
        archive(postProcessObjectKey, postProcessBucket, postProcessFolder, archivingOption);
        break;
      default:
        throw new IllegalStateException("Invalid post processing option : " +
          s3ConfigBean.postProcessingConfig.postProcessing.name());
    }
  }

  private void archive(String postProcessObjectKey, String postProcessBucket, String postProcessFolder,
                       S3ArchivingOption archivingOption) {
    String destBucket = s3ConfigBean.s3Config.bucket;
    switch (archivingOption) {
      case MOVE_TO_DIRECTORY:
        //no-op
        break;
      case MOVE_TO_BUCKET:
        destBucket = postProcessBucket;
        break;
      default:
        throw new IllegalStateException("Invalid Archive option : " + archivingOption.name());
    }
    String srcObjKey = postProcessObjectKey.substring(
      postProcessObjectKey.lastIndexOf(s3ConfigBean.s3Config.delimiter) + 1);
    String destKey = postProcessFolder + srcObjKey;
    AmazonS3Util.move(s3Client, s3ConfigBean.s3Config.bucket, postProcessObjectKey, destBucket, destKey);
  }

  private void delete(String postProcessObjectKey) {
    LOG.debug("Deleting previous file '{}'", postProcessObjectKey);
    s3Client.deleteObject(s3ConfigBean.s3Config.bucket, postProcessObjectKey);
  }

  public void handleCurrentObjectAsError() {
    //Move to error directory only if the error bucket and folder is specified and is different from
    //source bucket and folder
    postProcessOrErrorHandle(currentObject.getKey(), s3ConfigBean.errorConfig.errorHandlingOption,
      s3ConfigBean.errorConfig.errorBucket, s3ConfigBean.errorConfig.errorFolder,
      s3ConfigBean.errorConfig.archivingOption);
    currentObject = null;
  }

  public void postProcessOlderObjectIfNeeded(AmazonS3Source.S3Offset s3Offset) {
    //If sdc was shutdown after reading an object but before post processing it, handle it now.

    //The scenario is detected as follows:
    //  1. the current key must not be null
    //  2. offset must be -1
    //  3. An object with same key must exist in s3
    //  4. The timestamp of the object ins3 must be same as that of the timestamp in offset [It is possible that one
    //    uploads another object with the same name. We can avoid post processing it without producing records by
    //    comparing the timestamp on that object

    if(s3Offset.getKey() != null &&
      "-1".equals(s3Offset.getOffset())) {
      //conditions 1, 2 are met. Check for 3 and 4.
      S3ObjectSummary objectSummary = AmazonS3Util.getObjectSummary(s3Client, s3ConfigBean.s3Config.bucket, s3Offset.getKey());
      if(objectSummary != null &&
        objectSummary.getLastModified().compareTo(new Date(Long.parseLong(s3Offset.getTimestamp()))) == 0) {
        postProcessOrErrorHandle(s3Offset.getKey(), s3ConfigBean.postProcessingConfig.postProcessing,
          s3ConfigBean.postProcessingConfig.postProcessBucket, s3ConfigBean.postProcessingConfig.postProcessFolder,
          s3ConfigBean.postProcessingConfig.archivingOption);
      }
    }
    currentObject = null;
  }

  public static PathMatcher createPathMatcher(String pattern) {
    return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
  }

}
