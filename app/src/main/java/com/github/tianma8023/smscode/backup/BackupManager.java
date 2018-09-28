package com.github.tianma8023.smscode.backup;

import android.content.Context;

import com.github.tianma8023.smscode.BuildConfig;
import com.github.tianma8023.smscode.backup.exception.BackupInvalidException;
import com.github.tianma8023.smscode.backup.exception.VersionInvalidException;
import com.github.tianma8023.smscode.backup.exception.VersionMissedException;
import com.github.tianma8023.smscode.entity.SmsCodeRule;
import com.github.tianma8023.smscode.utils.StorageUtils;
import com.github.tianma8023.smscode.utils.XLog;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BackupManager {

    private static final String BACKUP_DIRECTORY = "SmsCodeExtractor";
    private static final String BACKUP_FILE_EXTENSION = ".scebak";
    private static final String BACKUP_FILE_NAME_PREFIX = "bak-";

    private static final String BACKUP_MIME_TYPE = "application/jason";
    private static final String BACKUP_FILE_AUTHORITY = BuildConfig.APPLICATION_ID + ".file";

    private BackupManager() {
    }

    public static File getBackupDir() {
        File sdcard = StorageUtils.getSDCardDir();
        return new File(sdcard, BACKUP_DIRECTORY);
    }

    public static String getBackupFileExtension() {
        return BACKUP_FILE_EXTENSION;
    }

    public static String getDefaultBackupFilename() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String dateStr = sdf.format(new Date());
        File backupDir = getBackupDir();
        String basename = BACKUP_FILE_NAME_PREFIX + dateStr;
        String filename = basename + BACKUP_FILE_EXTENSION;
        for (int i = 2; new File(backupDir, filename).exists(); i++) {
            filename = basename + "-" + i + BACKUP_FILE_EXTENSION;
        }
        return filename;
    }

    public static File[] getBackupFiles() {
        File backupDir = getBackupDir();
        File[] files = backupDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(BACKUP_FILE_EXTENSION);
            }
        });

        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return f1.getName().compareTo(f2.getName());
                }
            });
        }
        return files;
    }

    public static ExportResult exportRuleList(File file, List<SmsCodeRule> ruleList) {
        File parentFile = file.getParentFile();
        if (parentFile != null && !parentFile.exists()) {
            parentFile.mkdirs();
        }

        RuleExporter exporter = null;
        try {
            exporter = new RuleExporter(file);
            exporter.doExport(ruleList);
            return ExportResult.SUCCESS;
        } catch (IOException e) {
            return ExportResult.FAILED;
        } finally {
            if (exporter != null) {
                exporter.close();
            }
        }
    }

    public static ImportResult importRuleList(Context context, File file, boolean retain) {
        RuleImporter ruleImporter = null;
        try {
            ruleImporter = new RuleImporter(file);
            ruleImporter.doImport(context, retain);
            return ImportResult.SUCCESS;
        } catch (IOException e) {
            XLog.e("Error occurs in importRuleList", e);
            return ImportResult.READ_FAILED;
        } catch (VersionMissedException e) {
            XLog.e("Error occurs in importRuleList", e);
            return ImportResult.VERSION_MISSED;
        } catch (VersionInvalidException e) {
            XLog.e("Error occurs in importRuleList", e);
            return ImportResult.VERSION_UNKNOWN;
        } catch (BackupInvalidException e) {
            XLog.e("Error occurs in importRuleList", e);
            return ImportResult.BACKUP_INVALID;
        } finally {
            if (ruleImporter != null) {
                ruleImporter.close();
            }
        }
    }
}