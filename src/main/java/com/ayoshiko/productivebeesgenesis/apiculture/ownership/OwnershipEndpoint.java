package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity;
import java.util.concurrent.CompletionStage;

/** 主线程物理适配边界；保存回执必须来自目标区块写入完成，不能用 setChanged 代替。 */
public interface OwnershipEndpoint {
	void validate(NetworkIdentity network, MemberClaim claim);
	void freeze(NetworkIdentity network, MemberClaim claim);
	boolean matches(NetworkIdentity network, MemberClaim claim, MemberBinding.Mode mode);
	/** 将内存待产出转为可持久化状态；未完成时不得捕获或清空源。 */
	boolean prepare();
	AssetImage capture();
	void seal(AssetImage expected);
	boolean empty();
	void mode(MemberBinding.Mode mode);
	/** 必须在改动目标前验证完整容量、注册表和组件；返回失败不能部分写入。 */
	void validateReturn(AssetImage assets);
	void restore(AssetImage assets);
	CompletionStage<Void> saveReturn();
	void release();
	void quarantine(String reason);
}
