/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.test.values.extension.connection;

import org.mule.runtime.extension.api.annotation.Alias;
import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.annotation.param.ParameterGroup;
import org.mule.runtime.extension.api.annotation.values.OfValues;
import org.mule.test.values.extension.GroupWithValuesParameter;
import org.mule.test.values.extension.resolver.WithRequiredParameterFromGroupLegacyValueProvider;

@Alias("WithValuesWithRequiredParamsFromShowInDslGroup")
public class ConnectionWithValuesWithRequiredParamsFromShowInDslGroup extends AbstractConnectionProvider {

  @Parameter
  @OfValues(WithRequiredParameterFromGroupLegacyValueProvider.class)
  String valueParam;

  @ParameterGroup(name = "someGroup", showInDsl = true)
  GroupWithValuesParameter paramGroup;
}