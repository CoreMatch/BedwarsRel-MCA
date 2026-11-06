package io.github.bedwarsrel.mca;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.Test;

public class ReportClassifierTest {

  @Test
  public void twoHundredRangeIsSuccess() {
    assertEquals(ReportClassifier.Category.SUCCESS, ReportClassifier.classifyStatus(200));
    assertEquals(ReportClassifier.Category.SUCCESS, ReportClassifier.classifyStatus(201));
    assertEquals(ReportClassifier.Category.SUCCESS, ReportClassifier.classifyStatus(204));
  }

  @Test
  public void fourHundredRangeIsNeverRetried() {
    assertEquals(ReportClassifier.Category.CLIENT_ERROR, ReportClassifier.classifyStatus(400));
    assertEquals(ReportClassifier.Category.CLIENT_ERROR, ReportClassifier.classifyStatus(401));
    assertEquals(ReportClassifier.Category.CLIENT_ERROR, ReportClassifier.classifyStatus(403));
    // 404 mca_user_not_found / 409 mca_user_not_provisioned: retrying cannot fix them
    assertEquals(ReportClassifier.Category.CLIENT_ERROR, ReportClassifier.classifyStatus(404));
    assertEquals(ReportClassifier.Category.CLIENT_ERROR, ReportClassifier.classifyStatus(409));
    assertEquals(ReportClassifier.Category.CLIENT_ERROR, ReportClassifier.classifyStatus(499));
  }

  @Test
  public void fiveHundredRangeIsRetried() {
    assertEquals(ReportClassifier.Category.RETRYABLE, ReportClassifier.classifyStatus(500));
    assertEquals(ReportClassifier.Category.RETRYABLE, ReportClassifier.classifyStatus(502));
    assertEquals(ReportClassifier.Category.RETRYABLE, ReportClassifier.classifyStatus(503));
  }

  @Test
  public void unexpectedStatusClassesAreTreatedAsTransient() {
    assertEquals(ReportClassifier.Category.RETRYABLE, ReportClassifier.classifyStatus(302));
    assertEquals(ReportClassifier.Category.RETRYABLE, ReportClassifier.classifyStatus(100));
  }

  @Test
  public void networkFailuresAreRetried() {
    assertEquals(ReportClassifier.Category.RETRYABLE,
        ReportClassifier.classifyException(new IOException("connection refused")));
    assertEquals(ReportClassifier.Category.RETRYABLE,
        ReportClassifier.classifyException(new SocketTimeoutException("read timed out")));
  }
}
