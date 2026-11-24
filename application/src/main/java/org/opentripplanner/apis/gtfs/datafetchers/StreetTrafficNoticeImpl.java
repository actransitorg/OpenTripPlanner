package org.opentripplanner.apis.gtfs.datafetchers;

import graphql.schema.DataFetcher;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.opentripplanner.apis.gtfs.generated.GraphQLDataFetchers;
import org.opentripplanner.street.model.traffic.StreetTrafficNotice;

public class StreetTrafficNoticeImpl implements GraphQLDataFetchers.GraphQLStreetTrafficNotice {

  @Override
  public DataFetcher<String> source() {
    return env -> env.<StreetTrafficNotice>getSource().source();
  }

  @Override
  public DataFetcher<String> sourceId() {
    return env -> env.<StreetTrafficNotice>getSource().sourceId();
  }

  @Override
  public DataFetcher<String> basis() {
    return env -> env.<StreetTrafficNotice>getSource().basis();
  }

  @Override
  public DataFetcher<String> summary() {
    return env -> env.<StreetTrafficNotice>getSource().summary();
  }

  @Override
  public DataFetcher<OffsetDateTime> reportedAt() {
    return env -> {
      var time = env.<StreetTrafficNotice>getSource().reportedAt();
      return time == null ? null : time.atOffset(ZoneOffset.UTC);
    };
  }
}
