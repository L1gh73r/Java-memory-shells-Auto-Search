import json
import sys
import os

def filter_logs(input_file, output_file):
    print(f"Reading {input_file}...")
    try:
        with open(input_file, 'r', encoding='utf-8') as f:
            logs = json.load(f)
    except Exception as e:
        print(f"Error reading file: {e}")
        return

    print(f"Total logs: {len(logs)}")
    
    cutoff_index = -1
    for i, log in enumerate(logs):
        params = log.get('PARAMETERS', '')
        if 'ApplicationReadyEvent' in params:
            cutoff_index = i
            print(f"Found ApplicationReadyEvent at index {i}, timestamp: {log.get('DATE_TIME')}")
            break
    
    if cutoff_index != -1:
        filtered_logs = logs[cutoff_index + 1:]
        print(f"Logs after filtering: {len(filtered_logs)}")
        
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(filtered_logs, f, indent=2, ensure_ascii=False)
        print(f"Filtered logs saved to {output_file}")
    else:
        print("ApplicationReadyEvent not found. No filtering applied.")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Usage: python filter_logs.py <input_file> <output_file>")
    else:
        filter_logs(sys.argv[1], sys.argv[2])
