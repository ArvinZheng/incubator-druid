/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.zerofilledtopn;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.CharSource;
import com.google.common.io.LineProcessor;
import com.google.common.io.Resources;
import org.apache.druid.collections.StupidPool;
import org.apache.druid.common.config.NullHandling;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.impl.DelimitedParseSpec;
import org.apache.druid.data.input.impl.DimensionSchema;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.LongDimensionSchema;
import org.apache.druid.data.input.impl.StringDimensionSchema;
import org.apache.druid.data.input.impl.StringInputRowParser;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.query.FinalizeResultsQueryRunner;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerTestHelper;
import org.apache.druid.query.Result;
import org.apache.druid.query.aggregation.DoubleSumAggregatorFactory;
import org.apache.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.druid.query.aggregation.post.ArithmeticPostAggregator;
import org.apache.druid.query.aggregation.post.FieldAccessPostAggregator;
import org.apache.druid.query.context.DefaultResponseContext;
import org.apache.druid.query.topn.InvertedTopNMetricSpec;
import org.apache.druid.query.topn.NumericTopNMetricSpec;
import org.apache.druid.query.topn.TopNQuery;
import org.apache.druid.query.topn.TopNQueryBuilder;
import org.apache.druid.query.topn.TopNQueryConfig;
import org.apache.druid.query.topn.TopNQueryQueryToolChest;
import org.apache.druid.query.topn.TopNQueryRunnerFactory;
import org.apache.druid.query.topn.TopNResultValue;
import org.apache.druid.segment.IncrementalIndexSegment;
import org.apache.druid.segment.IndexBuilder;
import org.apache.druid.segment.Segment;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.segment.incremental.IncrementalIndex;
import org.apache.druid.segment.incremental.IncrementalIndexSchema;
import org.apache.druid.timeline.SegmentId;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@RunWith(Parameterized.class)
public class ZeroFilledTopNQueryRunnerTest
{
  private static final String RESOURCE_FILENAME = "segment.sample.data.csv";
  private static final String[] COLUMNS = new String[]{
      "eventdate",
      "dim1",
      "dim2",
      "dim3",
      "dim4",
      "dim5",
      "impressions",
      "views",
      "spend",
      "clicks"
  };
  private static final List<DimensionSchema> DIMENSION_SCHEMAS = Arrays.asList(
      new LongDimensionSchema("dim1"),
      new StringDimensionSchema("dim2"),
      new StringDimensionSchema("dim3"),
      new LongDimensionSchema("dim4"),
      new StringDimensionSchema("dim5"),
      new LongDimensionSchema("impressions"),
      new LongDimensionSchema("views"),
      new LongDimensionSchema("spend"),
      new LongDimensionSchema("clicks")
  );
  private static final DimensionsSpec DIMENSIONS_SPEC = new DimensionsSpec(
      DIMENSION_SCHEMAS,
      null,
      null
  );
  private final IndexBuilder indexBuilder;
  private final Function<IndexBuilder, Pair<Segment, Closeable>> finisher;
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();
  private Segment segment = null;
  private Closeable closeable = null;

  public ZeroFilledTopNQueryRunnerTest(
      String testName,
      Function<IndexBuilder, Pair<Segment, Closeable>> finisher
  ) throws IOException
  {
    final IncrementalIndexSchema schema = new IncrementalIndexSchema.Builder()
        .withDimensionsSpec(DIMENSIONS_SPEC)
        .withTimestampSpec(new TimestampSpec("eventdate", "YYYY-MM-dd", null))
        .withRollup(false)
        .withQueryGranularity(Granularities.NONE)
        .build();
    NullHandling.initializeForTests();
    final List<InputRow> rowList = buildInputRow();
    this.indexBuilder = IndexBuilder
        .create()
        .schema(schema)
        .rows(new ImmutableList.Builder().addAll(rowList.iterator()).build());
    this.finisher = finisher;
  }

  private List<InputRow> buildInputRow() throws IOException
  {
    final List<InputRow> rowList = new ArrayList<>();
    final CharSource source = loadTestDataFile();
    final StringInputRowParser parser = new StringInputRowParser(
        new DelimitedParseSpec(
            new TimestampSpec("eventdate", "YYYY-MM-dd", null),
            new DimensionsSpec(DIMENSION_SCHEMAS, null, null),
            ",",
            "\u0001",
            Arrays.asList(COLUMNS),
            false,
            0
        ),
        "utf8"
    );
    final AtomicLong startTime = new AtomicLong();
    int lineCount = source.readLines(
        new LineProcessor<Integer>()
        {
          boolean runOnce = false;
          int lineCount = 0;

          @Override
          public boolean processLine(String line)
          {
            if (!runOnce) {
              startTime.set(System.currentTimeMillis());
              runOnce = true;
            }
            rowList.add(parser.parse(line));
            ++lineCount;
            return true;
          }

          @Override
          public Integer getResult()
          {
            return lineCount;
          }
        }
    );
    System.out.println("Loaded "
                       + lineCount
                       + " lines in "
                       + (System.currentTimeMillis() - startTime.get())
                       + " millis.");
    return rowList;
  }

  @Parameterized.Parameters(name = "{0}")
  public static Collection<Object[]> constructorFeeder()
  {
    return makeConstructors();
  }

  public static Collection<Object[]> makeConstructors()
  {
    final List<Object[]> constructors = new ArrayList<>();
    final Map<String, Function<IndexBuilder, Pair<Segment, Closeable>>> finishers = ImmutableMap.of(
        "incremental", input -> {
          final IncrementalIndex index = input.buildIncrementalIndex();
          return Pair.of(new IncrementalIndexSegment(index, SegmentId.dummy("dummy")), index);
        }
    );
    for (final Map.Entry<String, Function<IndexBuilder, Pair<Segment, Closeable>>> finisherEntry : finishers.entrySet()) {
      final String testName = String.format(Locale.getDefault(), "finisher[%s]", finisherEntry.getKey());
      constructors.add(new Object[]{testName, finisherEntry.getValue()});
    }
    return constructors;
  }

  private static CharSource loadTestDataFile()
  {
    final URL resource = ZeroFilledTopNQueryRunnerTest.class.getClassLoader().getResource(RESOURCE_FILENAME);
    if (resource == null) {
      throw new IllegalArgumentException("cannot find resource " + RESOURCE_FILENAME);
    }
    CharSource stream = Resources.asByteSource(resource).asCharSource(StandardCharsets.UTF_8);
    return stream;
  }

  @Before
  public void setUp() throws Exception
  {
    indexBuilder.tmpDir(temporaryFolder.newFolder());
    final Pair<Segment, Closeable> pair = finisher.apply(indexBuilder);
    segment = pair.lhs;
    closeable = pair.rhs;
  }

  @After
  public void tearDown() throws Exception
  {
    closeable.close();
  }

  private Iterable<Result<TopNResultValue>> run(
      ZeroFilledTopNQueryRunnerFactory factory,
      ZeroFilledTopNQuery query,
      List<QueryRunner<Result<TopNResultValue>>> runners
  )
  {
    return new FinalizeResultsQueryRunner(
        factory.getToolchest().mergeResults(factory.mergeRunners(Executors.newSingleThreadExecutor(), runners)),
        factory.getToolchest()
    ).run(QueryPlus.wrap(query), new DefaultResponseContext())
     .toList();
  }


  private ZeroFilledTopNQueryRunnerFactory factory()
  {
    return new ZeroFilledTopNQueryRunnerFactory(
        new StupidPool<>(
            "test-bufferPool",
            () -> ByteBuffer.allocate(10485760)
        ),
        new ZeroFilledTopNQueryQueryToolChest(
            new TopNQueryConfig()
        ),
        QueryRunnerTestHelper.NOOP_QUERYWATCHER
    );
  }

  @Test
  public void testZeroFilledTopN_With_DescImp_Expect_NormalTopNResult()
  {
    Map<String, Object> context = new HashMap<>();
    context.put(ZeroFilledTopNQuery.DIM_VALUES, Lists.newArrayList("1", "2", "3", "7", "8", "10"));

    ZeroFilledTopNQuery query = new ZeroFilledTopNQueryBuilder()
        .dataSource("dummy")
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension("dim1")
        .metric("impressions")
        .threshold(3)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC.getIntervals())
        .context(context)
        .aggregators(Collections.singletonList(new LongSumAggregatorFactory("impressions", "impressions")))
        .build();

    ZeroFilledTopNQueryRunnerFactory factory = factory();

    Iterable<Result<TopNResultValue>> results = run(
        factory,
        query,
        Collections.singletonList(factory.createRunner(segment))
    );

    List<Result<TopNResultValue>> expectedResults = Collections.singletonList(
        new Result<>(
            new DateTime("2019-02-01T00:00:00.000Z", DateTimeZone.UTC),
            new TopNResultValue(
                ImmutableList.of(
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "8")
                        .put("impressions", 91L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "10")
                        .put("impressions", 61L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "7")
                        .put("impressions", 41L)
                        .build()
                )
            )
        )
    );
    TestHelper.assertExpectedResults(expectedResults, results);
  }

  @Test
  public void testZeroFilledTopN_With_DescImp_Expect_ZeroFilledTwo()
  {
    Map<String, Object> context = new HashMap<>();
    context.put(ZeroFilledTopNQuery.DIM_VALUES, Lists.newArrayList("11", "22", "33", "1", "2", "3"));

    ZeroFilledTopNQuery query = new ZeroFilledTopNQueryBuilder()
        .dataSource("dummy")
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension("dim1")
        .metric("impressions")
        .threshold(5)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC.getIntervals())
        .context(context)
        .aggregators(Collections.singletonList(new LongSumAggregatorFactory("impressions", "impressions")))
        .build();

    ZeroFilledTopNQueryRunnerFactory factory = factory();

    Iterable<Result<TopNResultValue>> results = run(
        factory,
        query,
        Lists.newArrayList(factory.createRunner(segment), factory.createRunner(segment))
    );

    List<Result<TopNResultValue>> expectedResults = Collections.singletonList(
        new Result<>(
            new DateTime("2019-02-01T00:00:00.000Z", DateTimeZone.UTC),
            new TopNResultValue(
                ImmutableList.of(
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "3")
                        .put("impressions", 6L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "2")
                        .put("impressions", 4L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "1")
                        .put("impressions", 2L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "11")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "22")
                        .put("impressions", 0L)
                        .build()
                )
            )
        )
    );
    TestHelper.assertExpectedResults(expectedResults, results);
  }

  @Test
  public void testZeroFilledTopN_With_OneSegmentDescImp_Expect_ZeroFilledThree()
  {
    Map<String, Object> context = new HashMap<>();
    context.put(ZeroFilledTopNQuery.DIM_VALUES, Lists.newArrayList("11", "22", "33", "1", "2", "3"));

    ZeroFilledTopNQuery query = new ZeroFilledTopNQueryBuilder()
        .dataSource("dummy")
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension("dim1")
        .metric(new InvertedTopNMetricSpec(new NumericTopNMetricSpec("impressions")))
        .threshold(5)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC.getIntervals())
        .context(context)
        .aggregators(Collections.singletonList(new LongSumAggregatorFactory("impressions", "impressions")))
        .build();

    ZeroFilledTopNQueryRunnerFactory factory = factory();

    Iterable<Result<TopNResultValue>> results = run(
        factory,
        query,
        Collections.singletonList(factory.createRunner(segment))
    );

    List<Result<TopNResultValue>> expectedResults = Collections.singletonList(
        new Result<>(
            new DateTime("2019-02-01T00:00:00.000Z", DateTimeZone.UTC),
            new TopNResultValue(
                ImmutableList.of(
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "11")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "22")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "33")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "1")
                        .put("impressions", 1L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "2")
                        .put("impressions", 2L)
                        .build()
                )
            )
        )
    );
    TestHelper.assertExpectedResults(expectedResults, results);
  }

  @Test
  public void testZeroFilledTopN_With_AscImp_Expect_ZeroFilledThree()
  {
    Map<String, Object> context = new HashMap<>();
    context.put(ZeroFilledTopNQuery.DIM_VALUES, Lists.newArrayList("11", "22", "33", "1", "2", "3"));

    ZeroFilledTopNQuery query = new ZeroFilledTopNQueryBuilder()
        .dataSource("dummy")
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension("dim1")
        .metric(new InvertedTopNMetricSpec(new NumericTopNMetricSpec("impressions")))
        .threshold(5)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC.getIntervals())
        .context(context)
        .aggregators(Collections.singletonList(new LongSumAggregatorFactory("impressions", "impressions")))
        .build();

    ZeroFilledTopNQueryRunnerFactory factory = factory();
    Iterable<Result<TopNResultValue>> results = run(
        factory,
        query,
        Lists.newArrayList(factory.createRunner(segment), factory.createRunner(segment))
    );

    List<Result<TopNResultValue>> expectedResults = Collections.singletonList(
        new Result<>(
            new DateTime("2019-02-01T00:00:00.000Z", DateTimeZone.UTC),
            new TopNResultValue(
                ImmutableList.of(
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "11")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "22")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "33")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "1")
                        .put("impressions", 2L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "2")
                        .put("impressions", 4L)
                        .build()
                )
            )
        )
    );
    TestHelper.assertExpectedResults(expectedResults, results);
  }

  @Test
  public void testZeroFilledTopN_With_NoDataReturnFromSegment_Expect_ZeroFilledAll()
  {
    Map<String, Object> context = new HashMap<>();
    context.put(ZeroFilledTopNQuery.DIM_VALUES, Lists.newArrayList("11", "22", "33", "1", "2", "3"));

    ZeroFilledTopNQuery query = new ZeroFilledTopNQueryBuilder()
        .dataSource("dummy")
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension("dim1")
        .metric(new InvertedTopNMetricSpec(new NumericTopNMetricSpec("impressions")))
        .threshold(5)
        .intervals("2019-12-01T00:00:00.000Z/2019-12-01T01:00:00.000Z")
        .context(context)
        .aggregators(Collections.singletonList(new LongSumAggregatorFactory("impressions", "impressions")))
        .build();

    ZeroFilledTopNQueryRunnerFactory factory = factory();
    Iterable<Result<TopNResultValue>> results = run(
        factory,
        query,
        Lists.newArrayList(factory.createRunner(segment), factory.createRunner(segment))
    );

    List<Result<TopNResultValue>> expectedResults = Collections.singletonList(
        new Result<>(
            new DateTime("2019-12-01T00:00:00.000Z", DateTimeZone.UTC),
            new TopNResultValue(
                ImmutableList.of(
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "1")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "11")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "2")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "22")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "3")
                        .put("impressions", 0L)
                        .build()
                )
            )
        )
    );
    TestHelper.assertExpectedResults(expectedResults, results);
  }

  @Test
  public void testTopN_With_DescImp_Expect_NormalTopNResult()
  {

    TopNQuery query = new TopNQueryBuilder()
        .dataSource("dummy")
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension("dim1")
        .metric("impressions")
        .threshold(3)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC.getIntervals())
        .aggregators(Collections.singletonList(new LongSumAggregatorFactory("impressions", "impressions")))
        .build();

    TopNQueryRunnerFactory factory = new TopNQueryRunnerFactory(
        new StupidPool<>(
            "test-bufferPool",
            () -> ByteBuffer.allocate(10485760)
        ),
        new TopNQueryQueryToolChest(
            new TopNQueryConfig()
        ),
        QueryRunnerTestHelper.NOOP_QUERYWATCHER
    );

    Iterable<Result<TopNResultValue>> results =
        new FinalizeResultsQueryRunner(
            factory.createRunner(segment),
            factory.getToolchest()
        ).run(QueryPlus.wrap(query), new DefaultResponseContext())
         .toList();

    List<Result<TopNResultValue>> expectedResults = Collections.singletonList(
        new Result<>(
            new DateTime("2019-02-01T00:00:00.000Z", DateTimeZone.UTC),
            new TopNResultValue(
                ImmutableList.of(
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "8")
                        .put("impressions", 91L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "10")
                        .put("impressions", 61L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "7")
                        .put("impressions", 41L)
                        .build()
                )
            )
        )
    );
    TestHelper.assertExpectedResults(expectedResults, results);

  }

  @Test
  public void testZeroFilledTopN_With_AscPostAgg_Expect_ZeroFilledThree_AndPostAggIsDouble()
  {
    Map<String, Object> context = new HashMap<>();
    context.put(ZeroFilledTopNQuery.DIM_VALUES, Lists.newArrayList("11", "22", "33", "1", "2", "3"));

    ZeroFilledTopNQuery query = new ZeroFilledTopNQueryBuilder()
        .dataSource("dummy")
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension("dim1")
        .metric(new InvertedTopNMetricSpec(new NumericTopNMetricSpec("impressions")))
        .threshold(5)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC.getIntervals())
        .context(context)
        .aggregators(Lists.newArrayList(
            new LongSumAggregatorFactory("impressions", "impressions"),
            new DoubleSumAggregatorFactory("clicks", "clicks")
        ))
        .postAggregators(Collections.singletonList(new ArithmeticPostAggregator(
                                                       "postAgg",
                                                       "+",
                                                       Lists.newArrayList(
                                                           new FieldAccessPostAggregator("impressions", "impressions"),
                                                           new FieldAccessPostAggregator("clicks", "clicks")
                                                       )
                                                   )
                         )
        )
        .build();

    ZeroFilledTopNQueryRunnerFactory factory = factory();
    Iterable<Result<TopNResultValue>> results = run(
        factory,
        query,
        Lists.newArrayList(factory.createRunner(segment), factory.createRunner(segment))
    );

    List<Result<TopNResultValue>> expectedResults = Collections.singletonList(
        new Result<>(
            new DateTime("2019-02-01T00:00:00.000Z", DateTimeZone.UTC),
            new TopNResultValue(
                ImmutableList.of(
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "11")
                        .put("impressions", 0L)
                        .put("clicks", 0.0d)
                        .put("postAgg", 0.0d)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "22")
                        .put("impressions", 0L)
                        .put("clicks", 0.0d)
                        .put("postAgg", 0.0d)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "33")
                        .put("impressions", 0L)
                        .put("clicks", 0.0d)
                        .put("postAgg", 0.0d)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "1")
                        .put("impressions", 2L)
                        .put("clicks", 0.0d)
                        .put("postAgg", 2.0d)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "2")
                        .put("impressions", 4L)
                        .put("clicks", 0.0d)
                        .put("postAgg", 4.0d)
                        .build()
                )
            )
        )
    );
    TestHelper.assertExpectedResults(expectedResults, results);
  }

  @Test
  public void testZeroFilledTopN_With_DescPostAgg_Expect_ZeroFilledTwo_AndPostAggIsDouble()
  {
    Map<String, Object> context = new HashMap<>();
    context.put(ZeroFilledTopNQuery.DIM_VALUES, Lists.newArrayList("11", "22", "33", "1", "2", "3"));

    ZeroFilledTopNQuery query = new ZeroFilledTopNQueryBuilder()
        .dataSource("dummy")
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension("dim1")
        .metric(new NumericTopNMetricSpec("impressions"))
        .threshold(5)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC.getIntervals())
        .context(context)
        .aggregators(Lists.newArrayList(
            new LongSumAggregatorFactory("impressions", "impressions"),
            new LongSumAggregatorFactory("clicks", "clicks")
        ))
        .postAggregators(Collections.singletonList(new ArithmeticPostAggregator(
                                                       "postAgg",
                                                       "+",
                                                       Lists.newArrayList(
                                                           new FieldAccessPostAggregator("impressions", "impressions"),
                                                           new FieldAccessPostAggregator("clicks", "clicks")
                                                       )
                                                   )
                         )
        )
        .build();

    ZeroFilledTopNQueryRunnerFactory factory = factory();
    Iterable<Result<TopNResultValue>> results = run(
        factory,
        query,
        Lists.newArrayList(factory.createRunner(segment), factory.createRunner(segment))
    );

    List<Result<TopNResultValue>> expectedResults = Collections.singletonList(
        new Result<>(
            new DateTime("2019-02-01T00:00:00.000Z", DateTimeZone.UTC),
            new TopNResultValue(
                ImmutableList.of(
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "3")
                        .put("impressions", 6L)
                        .put("clicks", 0L)
                        .put("postAgg", 6.0d)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "2")
                        .put("impressions", 4L)
                        .put("clicks", 0L)
                        .put("postAgg", 4.0d)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "1")
                        .put("impressions", 2L)
                        .put("clicks", 0L)
                        .put("postAgg", 2.0d)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "11")
                        .put("impressions", 0L)
                        .put("clicks", 0L)
                        .put("postAgg", 0.0d)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1", "22")
                        .put("impressions", 0L)
                        .put("clicks", 0L)
                        .put("postAgg", 0.0d)
                        .build()
                )
            )
        )
    );
    TestHelper.assertExpectedResults(expectedResults, results);
  }

  @Test
  public void testZeroFilledTopN_With_DimOutputNameConfigured_Expect_ZeroFilledWithOutputName()
  {
    Map<String, Object> context = new HashMap<>();
    context.put(ZeroFilledTopNQuery.DIM_VALUES, Lists.newArrayList("11", "22", "33", "1", "2", "3"));

    ZeroFilledTopNQuery query = new ZeroFilledTopNQueryBuilder()
        .dataSource("dummy")
        .granularity(QueryRunnerTestHelper.ALL_GRAN)
        .dimension("dim1", "dim1_output")
        .metric(new InvertedTopNMetricSpec(new NumericTopNMetricSpec("impressions")))
        .threshold(5)
        .intervals(QueryRunnerTestHelper.FULL_ON_INTERVAL_SPEC.getIntervals())
        .context(context)
        .aggregators(Collections.singletonList(new LongSumAggregatorFactory("impressions", "impressions")))
        .build();

    ZeroFilledTopNQueryRunnerFactory factory = factory();
    Iterable<Result<TopNResultValue>> results = run(
        factory,
        query,
        Lists.newArrayList(factory.createRunner(segment), factory.createRunner(segment))
    );

    List<Result<TopNResultValue>> expectedResults = Collections.singletonList(
        new Result<>(
            new DateTime("2019-02-01T00:00:00.000Z", DateTimeZone.UTC),
            new TopNResultValue(
                ImmutableList.of(
                    ImmutableMap.<String, Object>builder()
                        .put("dim1_output", "11")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1_output", "22")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1_output", "33")
                        .put("impressions", 0L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1_output", "1")
                        .put("impressions", 2L)
                        .build(),
                    ImmutableMap.<String, Object>builder()
                        .put("dim1_output", "2")
                        .put("impressions", 4L)
                        .build()
                )
            )
        )
    );
    TestHelper.assertExpectedResults(expectedResults, results);
  }

}
