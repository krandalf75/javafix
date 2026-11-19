package com.acme;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BadCode {
    public String load(String path) throws IOException {
        FileInputStream stream = new FileInputStream(path);
        int unused = 42;
        List<String> values = new ArrayList<>();
        values.add("acme");
        return values.get(0);
    }
}
