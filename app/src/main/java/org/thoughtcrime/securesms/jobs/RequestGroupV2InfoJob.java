package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;

import org.signal.zkgroup.VerificationFailedException;
import org.signal.zkgroup.groups.GroupMasterKey;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.v2.processing.GroupsV2StateProcessor;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.logging.Log;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.api.groupsv2.InvalidGroupStateException;
import org.whispersystems.signalservice.api.groupsv2.NoCredentialForRedemptionTimeException;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class RequestGroupV2InfoJob extends BaseJob {

  public static final String KEY = "RequestGroupV2InfoJob";

  @SuppressWarnings("unused")
  private static final String TAG = Log.tag(RequestGroupV2InfoJob.class);

  private static final String KEY_GROUP_ID    = "group_id";
  private static final String KEY_TO_REVISION = "to_revision";

  private final GroupId.V2 groupId;
  private final int        toRevision;

  public RequestGroupV2InfoJob(@NonNull GroupId.V2 groupId, int toRevision) {
    this(new Parameters.Builder()
                       .addConstraint(NetworkConstraint.KEY)
                       .setLifespan(TimeUnit.DAYS.toMillis(1))
                       .setMaxAttempts(Parameters.UNLIMITED)
                       //TODO: GV2 verify correct queue AND-207
                       .setQueue(PushProcessMessageJob.QUEUE)
                       .build(),
         groupId,
         toRevision);
  }

  /**
   * Get latest group state for group.
   */
  public RequestGroupV2InfoJob(@NonNull GroupId.V2 groupId) {
    this(groupId, GroupsV2StateProcessor.LATEST);
  }

  private RequestGroupV2InfoJob(@NonNull Parameters parameters, @NonNull GroupId.V2 groupId, int toRevision) {
    super(parameters);

    this.groupId    = groupId;
    this.toRevision = toRevision;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putString(KEY_GROUP_ID, groupId.toString())
                             .putInt(KEY_TO_REVISION, toRevision)
                             .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException, VerificationFailedException, InvalidGroupStateException {
    Log.i(TAG, "Updating group to revision " + toRevision);

    Optional<GroupDatabase.GroupRecord> group = DatabaseFactory.getGroupDatabase(context).getGroup(groupId);

    if (!group.isPresent()) {
      Log.w(TAG, "Group not found");
      return;
    }

    GroupMasterKey groupMasterKey = group.get().requireV2GroupProperties().getGroupMasterKey();

    new GroupsV2StateProcessor(context).forGroup(groupMasterKey)
                                       .updateLocalGroupToRevision(toRevision, System.currentTimeMillis());
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof PushNetworkException ||
           e instanceof NoCredentialForRedemptionTimeException;
  }

  @Override
  public void onFailure() {
  }

  public static final class Factory implements Job.Factory<RequestGroupV2InfoJob> {

    @Override
    public @NonNull RequestGroupV2InfoJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new RequestGroupV2InfoJob(parameters,
                                       GroupId.parseOrThrow(data.getString(KEY_GROUP_ID)).requireV2(),
                                       data.getInt(KEY_TO_REVISION));
    }
  }
}
