package ru.mrcrubs.lmsnode.service;

/** The job exists but has no output file/directory to serve. */
public class OutputNotAvailableException extends RuntimeException {
    public OutputNotAvailableException(String message) {
        super(message);
    }
}
