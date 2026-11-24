package org.opentripplanner.apis.transmodel.model.plan;

import graphql.Scalars;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import java.time.ZoneOffset;
import java.util.function.Function;
import org.opentripplanner.street.model.traffic.StreetTrafficNotice;

final class StreetTrafficNoticeType {

  private StreetTrafficNoticeType() {}

  static GraphQLObjectType create(GraphQLScalarType dateTimeScalar) {
    return GraphQLObjectType.newObject()
      .name("StreetTrafficNotice")
      .description("A source report that applies to a traveled street section.")
      .field(
        text(
          "basis",
          "How the source report applies, for example ADVISORY, REPORTED_DELAY or MEASURED_SPEED.",
          StreetTrafficNotice::basis
        )
      )
      .field(text("source", "Publishing data source.", StreetTrafficNotice::source))
      .field(
        text(
          "sourceId",
          "Event or detector identifier in the source feed.",
          StreetTrafficNotice::sourceId
        )
      )
      .field(text("summary", "Description of the condition.", StreetTrafficNotice::summary))
      .field(
        GraphQLFieldDefinition.newFieldDefinition()
          .name("reportedAt")
          .description("Time the source published or observed the condition, when available.")
          .type(dateTimeScalar)
          .dataFetcher(env -> {
            var time = env.<StreetTrafficNotice>getSource().reportedAt();
            return time == null ? null : time.atZone(ZoneOffset.UTC);
          })
      )
      .build();
  }

  private static GraphQLFieldDefinition text(
    String name,
    String description,
    Function<StreetTrafficNotice, String> value
  ) {
    return GraphQLFieldDefinition.newFieldDefinition()
      .name(name)
      .description(description)
      .type(GraphQLNonNull.nonNull(Scalars.GraphQLString))
      .dataFetcher(env -> value.apply(env.getSource()))
      .build();
  }
}
