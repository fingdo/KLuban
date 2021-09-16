package com.forjrking.lubankt.cache;

import java.io.File;
import java.io.IOException;

/**
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public interface DiskUsage {

    void touch(File file) throws IOException;

}