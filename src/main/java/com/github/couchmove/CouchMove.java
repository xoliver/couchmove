package com.github.couchmove;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.query.N1qlQuery;
import com.couchbase.client.java.view.DesignDocument;
import com.github.couchmove.exception.CouchMoveException;
import com.github.couchmove.pojo.ChangeLog;
import com.github.couchmove.pojo.Status;
import com.github.couchmove.pojo.Type;
import com.github.couchmove.pojo.Type.Constants;
import com.github.couchmove.service.ChangeLockService;
import com.github.couchmove.service.ChangeLogDBService;
import com.github.couchmove.service.ChangeLogFileService;
import com.github.couchmove.utils.Utils;
import com.google.common.base.Stopwatch;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.github.couchmove.pojo.Status.*;

/**
 * Couchmove Runner
 *
 * @author ctayeb
 * Created on 03/06/2017
 */
@NoArgsConstructor(access = AccessLevel.PACKAGE)
public class CouchMove {

    public static final String DEFAULT_MIGRATION_PATH = "db/migration";

    private static final Logger logger = LoggerFactory.getLogger(CouchMove.class);

    private String bucketName;

    private ChangeLockService lockService;

    @Setter(AccessLevel.PACKAGE)
    private ChangeLogDBService dbService;

    private ChangeLogFileService fileService;

    /**
     * Initialize a {@link CouchMove} instance with default migration path : {@value DEFAULT_MIGRATION_PATH}
     *
     * @param bucket Couchbase {@link Bucket} to execute the migrations on
     */
    public CouchMove(Bucket bucket) {
        this(bucket, DEFAULT_MIGRATION_PATH);
    }

    /**
     * Initialize a {@link CouchMove} instance
     *
     * @param bucket     Couchbase {@link Bucket} to execute the migrations on
     * @param changePath absolute or relative path of the migration folder containing {@link ChangeLog}
     */
    public CouchMove(Bucket bucket, String changePath) {
        logger.info("Connected to bucket '{}'", bucketName = bucket.name());
        lockService = new ChangeLockService(bucket);
        dbService = new ChangeLogDBService(bucket);
        fileService = new ChangeLogFileService(changePath);
    }

    /**
     * Launch the migration process :
     * <ol>
     *     <li> Tries to acquire Couchbase {@link Bucket} lock
     *     <li> Fetch all {@link ChangeLog}s from migration folder
     *     <li> Fetch corresponding {@link ChangeLog}s from {@link Bucket}
     *     <li> Execute found {@link ChangeLog}s : {@link CouchMove#executeMigration(List)}
     * </ol>
     */
    public void migrate() {
        logger.info("Begin bucket '{}' migration", bucketName);
        try {
            // Acquire bucket lock
            if (!lockService.acquireLock()) {
                logger.error("CouchMove did not acquire bucket '{}' lock. Exiting", bucketName);
                throw new CouchMoveException("Unable to acquire lock");
            }

            // Fetching ChangeLogs from migration directory
            List<ChangeLog> changeLogs = fileService.fetch();
            if (changeLogs.isEmpty()) {
                logger.info("CouchMove did not find any migration scripts");
                return;
            }

            // Fetching corresponding ChangeLogs from bucket
            changeLogs = dbService.fetchAndCompare(changeLogs);

            // Executing migration
            executeMigration(changeLogs);
        } catch (Exception e) {
            throw new CouchMoveException("Unable to migrate", e);
        } finally {
            // Release lock
            lockService.releaseLock();
        }
        logger.info("CouchMove has finished his job");
    }

    /**
     * Execute the {@link ChangeLog}s
     * <ul>
     *      <li> If {@link ChangeLog#version} is lower than last executed one, ignore it and mark it as {@link Status#SKIPPED}
     *      <li> If an {@link Status#EXECUTED} ChangeLog was modified, fail
     *      <li> If an {@link Status#EXECUTED} ChangeLog description was modified, update it
     *      <li> Otherwise apply the ChangeLog : {@link CouchMove#executeMigration(ChangeLog, int)}
     *  </ul>
     *
     * @param changeLogs to execute
     */
    void executeMigration(List<ChangeLog> changeLogs) {
        logger.info("Executing migration scripts...");
        int migrationCount = 0;
        // Get version and order of last executed changeLog
        String lastVersion = "";
        int lastOrder = 0;
        Optional<ChangeLog> lastExecutedChangeLog = changeLogs.stream()
                .filter(c -> c.getStatus() == EXECUTED)
                .max(Comparator.naturalOrder());
        if (lastExecutedChangeLog.isPresent()) {
            lastVersion = lastExecutedChangeLog.get().getVersion();
            lastOrder = lastExecutedChangeLog.get().getOrder();
        }

        for (ChangeLog changeLog : changeLogs) {
            if (changeLog.getStatus() == EXECUTED) {
                lastVersion = changeLog.getVersion();
                lastOrder = changeLog.getOrder();
            }
        }

        for (ChangeLog changeLog : changeLogs) {
            if (changeLog.getStatus() == EXECUTED) {
                if (changeLog.getCas() == null) {
                    logger.info("Updating changeLog '{}'", changeLog.getVersion());
                    dbService.save(changeLog);
                }
                continue;
            }

            if (changeLog.getStatus() == SKIPPED) {
                continue;
            }

            if (lastVersion.compareTo(changeLog.getVersion()) >= 0) {
                logger.warn("ChangeLog '{}' version is lower than last executed one '{}'. Skipping", changeLog.getVersion(), lastVersion);
                changeLog.setStatus(SKIPPED);
                dbService.save(changeLog);
                continue;
            }

            if (executeMigration(changeLog, lastOrder + 1)) {
                lastOrder++;
                lastVersion = changeLog.getVersion();
                migrationCount++;
            } else {
                throw new CouchMoveException("Migration failed");
            }
        }
        if (migrationCount == 0) {
            logger.info("No new migration scripts found");
        } else {
            logger.info("Executed {} migration scripts", migrationCount);
        }
    }

    /**
     * Execute the migration {@link ChangeLog}, and save it to Couchbase {@link Bucket}
     * <ul>
     *     <li> If the execution was successful, set the order and mark it as {@link Status#EXECUTED}
     *     <li> Otherwise, mark it as {@link Status#FAILED}
     * </ul>
     *
     * @param changeLog {@link ChangeLog} to execute
     * @param order the order to set if the execution was successful
     * @return true if the execution was successful, false otherwise
     */
    boolean executeMigration(ChangeLog changeLog, int order) {
        logger.info("Executing ChangeLog '{}'", changeLog.getVersion());
        Stopwatch sw = Stopwatch.createStarted();
        changeLog.setTimestamp(new Date());
        changeLog.setRunner(Utils.getUsername());
        if (doExecute(changeLog)) {
            logger.info("ChangeLog '{}' successfully executed", changeLog.getVersion());
            changeLog.setOrder(order);
            changeLog.setStatus(EXECUTED);
        } else {
            logger.error("Unable to execute ChangeLog '{}'", changeLog.getVersion());
            changeLog.setStatus(FAILED);
        }
        changeLog.setDuration(sw.elapsed(TimeUnit.MILLISECONDS));
        dbService.save(changeLog);
        return changeLog.getStatus() == EXECUTED;
    }

    /**
     * Applies the {@link ChangeLog} according to it's {@link ChangeLog#type} :
     * <ul>
     *     <li> {@link Type#DOCUMENTS} : Imports all {@value Constants#JSON} documents contained in the folder
     *     <li> {@link Type#N1QL} : Execute all {@link N1qlQuery} contained in the {@value Constants#N1QL} file
     *     <li> {@link Type#DESIGN_DOC} : Imports {@link DesignDocument} contained in the {@value Constants#JSON} document
     * </ul>
     * @param changeLog {@link ChangeLog} to apply
     * @return true if the execution was successful, false otherwise
     */
    boolean doExecute(ChangeLog changeLog) {
        try {
            switch (changeLog.getType()) {
                case DOCUMENTS:
                    dbService.importDocuments(fileService.readDocuments(changeLog.getScript()));
                    break;
                case N1QL:
                    dbService.executeN1ql(fileService.readFile(changeLog.getScript()));
                    break;
                case DESIGN_DOC:
                    dbService.importDesignDoc(changeLog.getDescription().replace(" ", "_"), fileService.readFile(changeLog.getScript()));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown ChangeLog Type '" + changeLog.getType() + "'");
            }
            return true;
        } catch (Exception e) {
            logger.error("Unable to import " + changeLog.getType().name().toLowerCase().replace("_", " ") + " : '" + changeLog.getScript() + "'", e);
            return false;
        }
    }
}

