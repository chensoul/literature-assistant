package com.yuyuan.literature.common.utils;

import io.github.haibiiin.json.repair.JSONRepair;
import io.github.haibiiin.json.repair.JSONRepairConfig;

public final class JSONRepairUtil {

	private static final JSONRepairConfig CONFIG = new JSONRepairConfig();

	private static final JSONRepair repair = new JSONRepair(CONFIG);

	static {
		CONFIG.enableExtractJSON();
	}

	private JSONRepairUtil() {
		throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
	}

	public static String repair(String inputJson) {
		return repair.handle(inputJson);
	}

}
