package com.acme;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BadCode {
    public int size(List<String> values) {
        int unused = 1;
        return values.size() == 0 ? 0 : new ArrayList<>(values).size();
    }

    public int open(String path) throws IOException {
        FileInputStream stream = new FileInputStream(path);
        return stream.available();
    }
}
