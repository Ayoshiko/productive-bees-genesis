/**
	 * 网络包定义与编解码
	 * <br/>
	 * 负责：
	 * <ol>
	 *   <li>客户端到服务端的数据包（如配置同步）</li>
	 *   <li>StreamCodec 编解码器定义</li>
	 *   <li>输入校验与权限验证</li>
	 * </ol>
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.network;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
