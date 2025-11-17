package com.workflow.service;

import com.opencsv.CSVReader;
import com.workflow.config.WorkflowConfig;
import com.workflow.dto.BatchInfo;
import com.workflow.dto.CsvDataItem;
import com.workflow.dto.CsvDataRequest;
import com.workflow.dto.CsvDataResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class CsvDataService {

    @Autowired
    private WorkflowConfig config;

    public CsvDataResponse readCsvData(CsvDataRequest request) {
        try {
            String fullPath = resolveFilePath(request.getFilePath());
            File file = new File(fullPath);

            if (!file.exists()) {
                return CsvDataResponse.builder()
                        .success(false)
                        .error("CSV文件未找到: " + request.getFilePath())
                        .searchPath(fullPath)
                        .build();
            }

            int totalRows = countDataRows(file);

            int batchSize = request.getBatchSize();
            int currentBatch = request.getCurrentBatch();
            int totalBatches = (totalRows + batchSize - 1) / batchSize;

            if (currentBatch < 0 || currentBatch >= totalBatches) {
                return CsvDataResponse.builder()
                        .success(false)
                        .error("批次索引无效。当前批次: " + currentBatch + ", 总批次数: " + totalBatches)
                        .build();
            }

            List<CsvDataItem> data = readBatchData(file, batchSize, currentBatch);

            BatchInfo batchInfo = BatchInfo.builder()
                    .currentBatch(currentBatch)
                    .batchSize(batchSize)
                    .totalBatches(totalBatches)
                    .totalRows(totalRows)
                    .currentBatchSize(data.size())
                    .hasNextBatch(currentBatch < totalBatches - 1)
                    .hasPrevBatch(currentBatch > 0)
                    .build();

            return CsvDataResponse.builder()
                    .success(true)
                    .data(data)
                    .batchInfo(batchInfo)
                    .filePath(fullPath)
                    .build();

        } catch (Exception e) {
            log.error("读取CSV文件时发生错误", e);
            return CsvDataResponse.builder()
                    .success(false)
                    .error("读取CSV文件时发生错误: " + e.getMessage())
                    .build();
        }
    }

    private String resolveFilePath(String filePath) {
        if (Paths.get(filePath).isAbsolute()) {
            return filePath;
        } else {
            String csvDir = config.getData().getCsvBaseDir();
            if (!filePath.toLowerCase().endsWith(".csv")) {
                filePath += ".csv";
            }
            return Paths.get(csvDir, filePath).toString();
        }
    }

    private int countDataRows(File file) throws Exception {
        int totalDataRows = 0;
        try (CSVReader reader = new CSVReader(new FileReader(file))) {
            String[] firstLine = reader.readNext();
            if (firstLine != null && firstLine.length >= 4) {
                if (!isHeaderRow(firstLine)) {
                    totalDataRows++;
                }
            }
            String[] nextLine;
            while ((nextLine = reader.readNext()) != null) {
                if (nextLine.length >= 4) {
                    totalDataRows++;
                }
            }
        }
        return totalDataRows;
    }

    private List<CsvDataItem> readBatchData(File file, int batchSize, int currentBatch) throws Exception {
        List<CsvDataItem> data = new ArrayList<>();
        int startIndex = currentBatch * batchSize;
        int endIndex = startIndex + batchSize;
        int currentIndex = 0;
        int globalId = 1;

        try (CSVReader reader = new CSVReader(new FileReader(file))) {
            String[] nextLine;

            String[] firstLine = reader.readNext();
            if (firstLine != null && firstLine.length >= 4) {
                if (!isHeaderRow(firstLine)) {
                    if (currentIndex >= startIndex && currentIndex < endIndex) {
                        data.add(createCsvDataItem(firstLine, globalId++));
                    }
                    currentIndex++;
                }
            }

            while ((nextLine = reader.readNext()) != null && nextLine.length >= 4) {
                if (currentIndex >= startIndex && currentIndex < endIndex) {
                    data.add(createCsvDataItem(nextLine, globalId));
                }
                currentIndex++;
                globalId++;

                if (currentIndex >= endIndex) {
                    break;
                }
            }
        }

        return data;
    }

    private boolean isHeaderRow(String[] row) {
        if (row.length < 4) return false;
        String firstCol = row[0].toLowerCase().replace("\"", "");
        return firstCol.equals("ownerclass") || firstCol.equals("caller_class") || firstCol.equals("class");
    }

    private CsvDataItem createCsvDataItem(String[] row, int id) {
        return CsvDataItem.builder()
                .id(id)
                .callerClass(row[0].replace("\"", "").trim())
                .field(row[1].replace("\"", "").trim())
                .targetClass(row[2].replace("\"", "").trim())
                .method(row[3].replace("\"", "").trim())
                .build();
    }
}