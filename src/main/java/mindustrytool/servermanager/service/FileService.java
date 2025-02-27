package mindustrytool.servermanager.service;

import org.springframework.stereotype.Service;

import mindustrytool.servermanager.types.request.AddServerFileRequest;
import mindustrytool.servermanager.types.response.ServerFileDto;
import mindustrytool.servermanager.utils.ApiError;
import mindustrytool.servermanager.utils.Utils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@Service
public class FileService {

	private final Long MAX_FILE_SIZE = 5000000l;

	public Mono<File> getServerFile(String basePath, String path) {
		if (path.contains("..") || path.contains("./")) {
			return ApiError.notFound(path, File.class);
		}

		return Mono.just(new File(basePath + path))//
				.filter(file -> file.exists())//
				.switchIfEmpty(ApiError.notFound(path, File.class));
	}

	public Flux<ServerFileDto> getFiles(String basePath, String path) {
		return getServerFile(basePath, path) //
				.filter(file -> file.length() < MAX_FILE_SIZE)//
				.switchIfEmpty(ApiError.badRequest("file-too-big"))//
				.flatMapMany(file -> {
					try {
						return file.isDirectory()//
								? Flux.fromArray(file.listFiles())//
										.map(child -> new ServerFileDto()//
												.name(child.getName())//
												.size(child.length())//
												.directory(child.isDirectory()))
								: Flux.just(new ServerFileDto()//
										.name(file.getName())//
										.directory(file.isDirectory())//
										.size(file.length())//
										.data(Files.readString(file.toPath())));
					} catch (IOException e) {
						return Mono.error(e);
					}
				});
	}

	public Mono<Void> addFile(String basePath, String path, AddServerFileRequest request) {
		var file = request.getFile();

		return getServerFile(basePath, path)//
				.map(serverFile -> new File(serverFile, file.filename()))//
				.flatMap(serverFile -> Utils.readAllBytes(file)//
						.doOnNext(bytes -> {
							try {
								Files.write(serverFile.toPath(), bytes);
							} catch (IOException e) {
								e.printStackTrace();
							}
						}))
				.then();
	}

	public Mono<Void> deleteFile(String basePath, String path) {
		return getServerFile(basePath, path)//
				.map(serverFile -> deleteFile(serverFile))//
				.flatMap(result -> result ? Mono.just(result) : ApiError.notFound(path, File.class))//
				.then();
	}

	private boolean deleteFile(File file) {
		if (file.isDirectory()) {
			for (File child : file.listFiles()) {
				deleteFile(child);
			}
		}
		return file.delete();
	}
}
