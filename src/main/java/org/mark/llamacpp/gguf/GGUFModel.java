package org.mark.llamacpp.gguf;

import java.util.List;



public class GGUFModel {

	/**
	 * 模型的名字
	 */
	private String name;

	/**
	 * 架构名
	 */
	private String architecture;

	/**
	 * 全部的文件信息。
	 */
	private long totalSize;

	/**
	 * 全部的元信息
	 */
	private List<GGUFMetaData> metaDataList;

	/**
	 * 	
	 */
	private GGUFMetaData primaryModel;

	/**
	 * 	视觉模块
	 */
	private GGUFMetaData mmproj;

	/**
	 * 	路径。
	 */
	private String path;

	/**
	 * 模型别名（用于显示）
	 */
	private String alias;
	
	/**
	 * 	模型ID
	 */
	private String modelId;
	
	
	/**
	 * 	是否为偏好模型
	 */
	private boolean favourite = false;

	/**
	 * 	是否通过启动配置关联了草稿模型（--spec-type / --spec-draft-model）
	 */
	private boolean hasDraftModel = false;


	public GGUFModel(String name, String path) {
		this.path = path;
		this.name = name;
		this.metaDataList = new java.util.ArrayList<>();
		this.modelId = this.name;
	}

	public void addMetaData(GGUFMetaData metaData) {
		this.metaDataList.add(metaData);
	}

	public void setPrimaryModel(GGUFMetaData primaryModel) {
		this.primaryModel = primaryModel;
		// 可以在这里更新模型的其他属性
		if (this.architecture == null) {
			this.architecture = primaryModel.getStringValue("general.architecture");
		}
	}
	
	/**
	 * 	模型的ID，但是直接使用{@link #getName()}
	 * @return
	 */
	public String getModelId() {
		return this.modelId;
	}

	public String getName() {
		return this.name;
	}

	public String getAlias() {
		return this.alias;
	}

	public void setAlias(String alias) {
		this.alias = alias;
	}
	
	public boolean isFavourite() {
		return favourite;
	}
	
	public void setFavourite(boolean favourite) {
		this.favourite = favourite;
	}

	public boolean hasDraftModel() {
		return hasDraftModel;
	}

	public void setHasDraftModel(boolean hasDraftModel) {
		this.hasDraftModel = hasDraftModel;
	}

	/**
	 * 	
	 * @return
	 */
	public String getPath() {
		return this.path;
	}
	
	
	public GGUFMetaData getPrimaryModel() {
		return this.primaryModel;
	}
	
	public Integer getFileType() {
		if (this.primaryModel == null) return null;
		return this.primaryModel.getFileType();
	}
	
	public String getQuantizationType() {
		if (this.primaryModel == null) return null;
		return this.primaryModel.getQuantizationType();
	}

	public void setMmproj(GGUFMetaData mmproj) {
		this.mmproj = mmproj;
	}

	public GGUFMetaData getMmproj() {
		return this.mmproj;
	}

	public void setMetaDataList(List<GGUFMetaData> metaDataList) {
		this.metaDataList = metaDataList;
	}

	public List<GGUFMetaData> getMetaDataList() {
		return this.metaDataList;
	}

	public long getSize() {
		return this.totalSize;
	}

	public void setSize(long totalSize) {
		this.totalSize = totalSize;
	}

	@Override
	public String toString() {
		return "Model{" + "name='" + name + '\'' + ", architecture='" + architecture + '\'' + ", parts="
				+ metaDataList.size() + '}';
	}
}
