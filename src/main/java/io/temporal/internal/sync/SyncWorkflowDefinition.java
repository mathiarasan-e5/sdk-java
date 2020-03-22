/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.sync;

interface SyncWorkflowDefinition {

  /**
   * Always called first. Usually {@link #execute(byte[])} is called after that and then potentially
   * multiple {@link #processSignal(String, byte[], long)} while execute is running. But in case of
   * signalWithStart {@link #processSignal(String, byte[], long)} can be called before the {@link
   * #execute(byte[])}.
   */
  void initialize();

  byte[] execute(byte[] input);

  void processSignal(String signalName, byte[] input, long eventId);
}