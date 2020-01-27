/*******************************************************************************
 * Copyright (c) 2016, 2018 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *  Miro Spoenemann (TypeFox) - extracted LanguageClientImpl
 *  Jan Koehnlein (TypeFox) - bug 521744
 *  Martin Lippert (Pivotal, Inc.) - bug 531030, 527902, 534637
 *  Kris De Volder (Pivotal, Inc.) - dynamic command registration
 *******************************************************************************/
package org.eclipse.lsp4e;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filebuffers.IFileBufferListener;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4e.LanguageServersRegistry.LanguageServerDefinition;
import org.eclipse.lsp4e.server.StreamConnectionProvider;
import org.eclipse.lsp4e.ui.Messages;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeActionCapabilities;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionKindCapabilities;
import org.eclipse.lsp4j.CodeActionLiteralSupportCapabilities;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CodeLensCapabilities;
import org.eclipse.lsp4j.ColorProviderCapabilities;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.DocumentHighlightCapabilities;
import org.eclipse.lsp4j.DocumentLinkCapabilities;
import org.eclipse.lsp4j.DocumentSymbolCapabilities;
import org.eclipse.lsp4j.ExecuteCommandCapabilities;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.FailureHandlingKind;
import org.eclipse.lsp4j.FormattingCapabilities;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.RangeFormattingCapabilities;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.RenameCapabilities;
import org.eclipse.lsp4j.ResourceOperationKind;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelpCapabilities;
import org.eclipse.lsp4j.SymbolCapabilities;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolKindCapabilities;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.TypeDefinitionCapabilities;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEditCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import org.eclipse.lsp4j.WorkspaceFoldersOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class LanguageServerWrapper {

	private IFileBufferListener fileBufferListener = new FileBufferListenerAdapter() {
		@Override
		public void bufferDisposed(IFileBuffer buffer) {
			Path filePath = new Path(buffer.getFileStore().toURI().getPath());
			disconnect(filePath);
		}

		@Override
		public void dirtyStateChanged(IFileBuffer buffer, boolean isDirty) {
			if (isDirty) {
				return;
			}
			Path filePath = new Path(buffer.getFileStore().toURI().getPath());
			DocumentContentSynchronizer documentListener = connectedDocuments.get(filePath);
			if (documentListener != null && documentListener.getModificationStamp() < buffer.getModificationStamp()) {
				documentListener.documentSaved(buffer.getModificationStamp());
			}
		}

	};

	@NonNull
	public final LanguageServerDefinition serverDefinition;
	@Nullable
	protected final IProject initialProject;
	@NonNull
	protected final Set<@NonNull IProject> allWatchedProjects;
	@NonNull
	protected Map<@NonNull IPath, @NonNull DocumentContentSynchronizer> connectedDocuments;
	@Nullable
	protected final IPath initialPath;

	protected StreamConnectionProvider lspStreamProvider;
	private Future<?> launcherFuture;
	private CompletableFuture<Void> initializeFuture;
	private LanguageServer languageServer;
	private ServerCapabilities serverCapabilities;

	/**
	 * Map containing unregistration handlers for dynamic capability registrations.
	 */
	private @NonNull Map<@NonNull String, @NonNull Runnable> dynamicRegistrations = new HashMap<>();
	private boolean initiallySupportsWorkspaceFolders = false;

	/* Backwards compatible constructor */
	public LanguageServerWrapper(@NonNull IProject project, @NonNull LanguageServerDefinition serverDefinition) {
		this(project, serverDefinition, null);
	}

	public LanguageServerWrapper(@NonNull LanguageServerDefinition serverDefinition, @Nullable IPath initialPath) {
		this(null, serverDefinition, initialPath);
	}

	/** Unified private constructor to set sensible defaults in all cases */
	private LanguageServerWrapper(@Nullable IProject project, @NonNull LanguageServerDefinition serverDefinition,
			@Nullable IPath initialPath) {
		this.initialProject = project;
		this.initialPath = initialPath;
		this.allWatchedProjects = new HashSet<>();
		this.serverDefinition = serverDefinition;
		this.connectedDocuments = new HashMap<>();
	}

	/**
	 * Starts a language server and triggers initialization. If language server is
	 * started and active, does nothing. If language server is inactive, restart it.
	 *
	 * @throws IOException
	 */
	public synchronized void start() throws IOException {
		Map<IPath, IDocument> filesToReconnect = Collections.emptyMap();
		if (this.languageServer != null) {
			if (isActive()) {
				return;
			} else {
				filesToReconnect = new HashMap<>();
				for (Entry<IPath, DocumentContentSynchronizer> entry : this.connectedDocuments.entrySet()) {
					filesToReconnect.put(entry.getKey(), entry.getValue().getDocument());
				}
				stop();
			}
		}
		try {
			if (LoggingStreamConnectionProviderProxy.shouldLog(serverDefinition.id)) {
				this.lspStreamProvider = new LoggingStreamConnectionProviderProxy(
						serverDefinition.createConnectionProvider(), serverDefinition.id);
			} else {
				this.lspStreamProvider = serverDefinition.createConnectionProvider();
			}
			this.lspStreamProvider.start();

			LanguageClientImpl client = serverDefinition.createLanguageClient();
			ExecutorService executorService = Executors.newCachedThreadPool();
			final InitializeParams initParams = new InitializeParams();
			initParams.setProcessId(getCurrentProcessId());

			URI rootURI = null;
			IProject project = this.initialProject;
			if (project != null && project.exists()) {
				rootURI = LSPEclipseUtils.toUri(this.initialProject);
				initParams.setRootUri(rootURI.toString());
				initParams.setRootPath(rootURI.getPath());
			} else {
				// This is required due to overzealous static analysis. Dereferencing
				// this.initialPath directly will trigger a "potential null"
				// warning/error. Checking for this.initialPath == null is not
				// enough.
				final IPath initialPath = this.initialPath;
				if (initialPath != null) {
					File projectDirectory = initialPath.toFile();
					if (projectDirectory.isFile()) {
						projectDirectory = projectDirectory.getParentFile();
					}
					initParams.setRootUri(LSPEclipseUtils.toUri(projectDirectory).toString());
				} else {
					initParams.setRootUri(LSPEclipseUtils.toUri(new File("/")).toString()); //$NON-NLS-1$
				}
			}
			Launcher<? extends LanguageServer> launcher = Launcher.createLauncher(client,
					serverDefinition.getServerInterface(), this.lspStreamProvider.getInputStream(),
					this.lspStreamProvider.getOutputStream(), executorService, consumer -> (message -> {
						consumer.consume(message);
						logMessage(message);
						URI root = initParams.getRootUri() != null ? URI.create(initParams.getRootUri()) : null;
						final StreamConnectionProvider currentConnectionProvider = this.lspStreamProvider;
						if (currentConnectionProvider != null && isActive()) {
							currentConnectionProvider.handleMessage(message, this.languageServer, root);
						}
					}));

			this.languageServer = launcher.getRemoteProxy();
			client.connect(languageServer, this);
			this.launcherFuture = launcher.startListening();

			String name = "Eclipse IDE"; //$NON-NLS-1$
			if (Platform.getProduct() != null) {
				name = Platform.getProduct().getName();
			}
			WorkspaceClientCapabilities workspaceClientCapabilities = new WorkspaceClientCapabilities();
			workspaceClientCapabilities.setApplyEdit(Boolean.TRUE);
			workspaceClientCapabilities.setExecuteCommand(new ExecuteCommandCapabilities(Boolean.TRUE));
			workspaceClientCapabilities.setSymbol(new SymbolCapabilities(Boolean.TRUE));
			workspaceClientCapabilities.setWorkspaceFolders(Boolean.TRUE);
			WorkspaceEditCapabilities editCapabilities = new WorkspaceEditCapabilities();
			editCapabilities.setDocumentChanges(Boolean.TRUE);
			editCapabilities.setResourceOperations(Arrays.asList(ResourceOperationKind.Create,
					ResourceOperationKind.Delete, ResourceOperationKind.Rename));
			editCapabilities.setFailureHandling(FailureHandlingKind.Undo);
			workspaceClientCapabilities.setWorkspaceEdit(editCapabilities);
			TextDocumentClientCapabilities textDocumentClientCapabilities = new TextDocumentClientCapabilities();
			textDocumentClientCapabilities
					.setCodeAction(
							new CodeActionCapabilities(
									new CodeActionLiteralSupportCapabilities(
											new CodeActionKindCapabilities(Arrays.asList(CodeActionKind.QuickFix,
													CodeActionKind.Refactor, CodeActionKind.RefactorExtract,
													CodeActionKind.RefactorInline, CodeActionKind.RefactorRewrite,
													CodeActionKind.Source, CodeActionKind.SourceOrganizeImports))),
									true));
			textDocumentClientCapabilities.setCodeLens(new CodeLensCapabilities());
			textDocumentClientCapabilities.setColorProvider(new ColorProviderCapabilities());
			CompletionItemCapabilities completionItemCapabilities = new CompletionItemCapabilities(Boolean.TRUE);
			completionItemCapabilities.setDocumentationFormat(Arrays.asList(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT));
			textDocumentClientCapabilities
					.setCompletion(new CompletionCapabilities(completionItemCapabilities));
			DefinitionCapabilities definitionCapabilities = new DefinitionCapabilities();
			definitionCapabilities.setLinkSupport(Boolean.TRUE);
			textDocumentClientCapabilities.setDefinition(definitionCapabilities);
			TypeDefinitionCapabilities typeDefinitionCapabilities = new TypeDefinitionCapabilities();
			typeDefinitionCapabilities.setLinkSupport(Boolean.TRUE);
			textDocumentClientCapabilities.setTypeDefinition(typeDefinitionCapabilities);
			textDocumentClientCapabilities.setDocumentHighlight(new DocumentHighlightCapabilities());
			textDocumentClientCapabilities.setDocumentLink(new DocumentLinkCapabilities());
			DocumentSymbolCapabilities documentSymbol = new DocumentSymbolCapabilities();
			documentSymbol.setHierarchicalDocumentSymbolSupport(true);
			documentSymbol.setSymbolKind(new SymbolKindCapabilities(Arrays.asList(SymbolKind.Array, SymbolKind.Boolean,
					SymbolKind.Class, SymbolKind.Constant, SymbolKind.Constructor, SymbolKind.Enum,
					SymbolKind.EnumMember, SymbolKind.Event, SymbolKind.Field, SymbolKind.File, SymbolKind.Function,
					SymbolKind.Interface, SymbolKind.Key, SymbolKind.Method, SymbolKind.Module, SymbolKind.Namespace,
					SymbolKind.Null, SymbolKind.Number, SymbolKind.Object, SymbolKind.Operator, SymbolKind.Package,
					SymbolKind.Property, SymbolKind.String, SymbolKind.Struct, SymbolKind.TypeParameter,
					SymbolKind.Variable)));
			textDocumentClientCapabilities.setDocumentSymbol(documentSymbol);
			textDocumentClientCapabilities.setFormatting(new FormattingCapabilities(Boolean.TRUE));
			HoverCapabilities hoverCapabilities = new HoverCapabilities();
			hoverCapabilities.setContentFormat(Arrays.asList(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT));
			textDocumentClientCapabilities.setHover(hoverCapabilities);
			textDocumentClientCapabilities.setOnTypeFormatting(null); // TODO
			textDocumentClientCapabilities.setRangeFormatting(new RangeFormattingCapabilities());
			textDocumentClientCapabilities.setReferences(new ReferencesCapabilities());
			textDocumentClientCapabilities.setRename(new RenameCapabilities());
			textDocumentClientCapabilities.setSignatureHelp(new SignatureHelpCapabilities());
			textDocumentClientCapabilities
					.setSynchronization(new SynchronizationCapabilities(Boolean.TRUE, Boolean.TRUE, Boolean.TRUE));
			initParams.setCapabilities(
					new ClientCapabilities(workspaceClientCapabilities, textDocumentClientCapabilities, lspStreamProvider.getExperimentalFeaturesPOJO()));
			initParams.setClientName(name);

			initParams.setInitializationOptions(this.lspStreamProvider.getInitializationOptions(rootURI));
			initParams.setTrace(this.lspStreamProvider.getTrace(rootURI));

			// no then...Async future here as we want this chain of operation to be sequential and
			// "atomic"-ish
			initializeFuture = languageServer.initialize(initParams).thenAccept(res -> {
				serverCapabilities = res.getCapabilities();
				this.initiallySupportsWorkspaceFolders = supportsWorkspaceFolders(serverCapabilities);
			}).thenRun(() -> {
				this.languageServer.initialized(new InitializedParams());
			});

			final Map<IPath, IDocument> toReconnect = filesToReconnect;
			initializeFuture.thenRunAsync(() -> {
				if (this.initialProject != null) {
					watchProject(this.initialProject, true);
				}
				for (Entry<IPath, IDocument> fileToReconnect : toReconnect.entrySet()) {
					try {
						connect(fileToReconnect.getKey(), fileToReconnect.getValue());
					} catch (IOException e) {
						LanguageServerPlugin.logError(e);
					}
				}
			});
			FileBuffers.getTextFileBufferManager().addFileBufferListener(fileBufferListener);
		} catch (Exception ex) {
			LanguageServerPlugin.logError(ex);
			stop();
		}
	}

	private static boolean supportsWorkspaceFolders(ServerCapabilities serverCapabilities) {
		return serverCapabilities != null && serverCapabilities.getWorkspace() != null
				&& serverCapabilities.getWorkspace().getWorkspaceFolders() != null
				&& Boolean.TRUE.equals(serverCapabilities.getWorkspace().getWorkspaceFolders().getSupported());
	}

	private Integer getCurrentProcessId() {
		String segment = ManagementFactory.getRuntimeMXBean().getName().split("@")[0]; //$NON-NLS-1$
		try {
			return Integer.valueOf(segment);
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private void logMessage(Message message) {
		if (message instanceof ResponseMessage && ((ResponseMessage) message).getError() != null
				&& ((ResponseMessage) message).getId()
						.equals(Integer.toString(ResponseErrorCode.RequestCancelled.getValue()))) {
			ResponseMessage responseMessage = (ResponseMessage) message;
			LanguageServerPlugin.logError(new ResponseErrorException(responseMessage.getError()));
		} else if (LanguageServerPlugin.DEBUG) {
			LanguageServerPlugin.logInfo(message.getClass().getSimpleName() + '\n' + message.toString());
		}
	}

	/**
	 * @return whether the underlying connection to language server is still active
	 */
	public boolean isActive() {
		return this.launcherFuture != null && !this.launcherFuture.isDone() && !this.launcherFuture.isCancelled();
	}

	synchronized void stop() {
		if (this.initializeFuture != null) {
			this.initializeFuture.cancel(true);
			this.initializeFuture = null;
		}

		this.serverCapabilities = null;
		this.dynamicRegistrations.clear();

		final Future<?> serverFuture = this.launcherFuture;
		final StreamConnectionProvider provider = this.lspStreamProvider;
		final LanguageServer languageServerInstance = this.languageServer;

		Runnable shutdownKillAndStopFutureAndProvider = () -> {
			if (languageServerInstance != null) {
				CompletableFuture<Object> shutdown = languageServerInstance.shutdown();
				try {
					shutdown.get(5, TimeUnit.SECONDS);
				}
				catch (Exception e) {
				}
			}

			if (serverFuture != null) {
				serverFuture.cancel(true);
			}

			if (languageServerInstance != null) {
				languageServerInstance.exit();
			}

			if (provider != null) {
				provider.stop();
			}
		};

		CompletableFuture.runAsync(shutdownKillAndStopFutureAndProvider);

		this.launcherFuture = null;
		this.lspStreamProvider = null;

		while (!this.connectedDocuments.isEmpty()) {
			disconnect(this.connectedDocuments.keySet().iterator().next());
		}
		this.languageServer = null;

		FileBuffers.getTextFileBufferManager().removeFileBufferListener(fileBufferListener);
	}

	/**
	 *
	 * @param file
	 * @param document
	 * @return null if not connection has happened, a future tracking the connection state otherwise
	 * @throws IOException
	 */
	public @Nullable CompletableFuture<LanguageServer> connect(@NonNull IFile file, IDocument document) throws IOException {
		return connect(file.getLocation(), document);
	}

	/**
	 *
	 * @param document
	 * @return null if not connection has happened, a future tracking the connection state otherwise
	 * @throws IOException
	 */
	public @Nullable CompletableFuture<LanguageServer> connect(IDocument document) throws IOException {
		IFile file = LSPEclipseUtils.getFile(document);

		if (file != null && file.exists()) {
			return connect(file, document);
		} else {
			URI uri = LSPEclipseUtils.toUri(document);
			if (uri != null) {
				return connect(new Path(LSPEclipseUtils.toUri(document).getPath()), document);
			}
		}
		return null;
	}

	protected synchronized void watchProject(IProject project, boolean isInitializationRootProject) {
		if (this.allWatchedProjects.contains(project)) {
			return;
		}
		if (isInitializationRootProject && !this.allWatchedProjects.isEmpty()) {
			return; // there can be only one root project
		}
		if (!isInitializationRootProject && !supportsWorkspaceFolderCapability()) {
			// multi project and WorkspaceFolder notifications not supported by this server
			// instance
			return;
		}
		this.allWatchedProjects.add(project);
		project.getWorkspace().addResourceChangeListener(event -> {
			if (project.equals(event.getResource()) && (event.getDelta().getKind() == IResourceDelta.MOVED_FROM
					|| event.getDelta().getKind() == IResourceDelta.REMOVED)) {
				unwatchProject(project);
			}
		}, IResourceChangeEvent.POST_CHANGE);
		if (supportsWorkspaceFolderCapability()) {
			WorkspaceFoldersChangeEvent event = new WorkspaceFoldersChangeEvent();
			event.getAdded().add(LSPEclipseUtils.toWorkspaceFolder(project));
			DidChangeWorkspaceFoldersParams params = new DidChangeWorkspaceFoldersParams();
			params.setEvent(event);
			this.languageServer.getWorkspaceService().didChangeWorkspaceFolders(params);
		}
	}

	private synchronized void unwatchProject(@NonNull IProject project) {
		this.allWatchedProjects.remove(project);
		// TODO? disconnect resources?
		if (supportsWorkspaceFolderCapability()) {
			WorkspaceFoldersChangeEvent event = new WorkspaceFoldersChangeEvent();
			WorkspaceFolder workspaceFolder = LSPEclipseUtils.toWorkspaceFolder(project);
			event.getRemoved().add(workspaceFolder);
			DidChangeWorkspaceFoldersParams params = new DidChangeWorkspaceFoldersParams();
			params.setEvent(event);
			this.languageServer.getWorkspaceService().didChangeWorkspaceFolders(params);
		}
	}

	/**
	 * Check whether this LS is suitable for provided project. Starts the LS if not
	 * already started.
	 *
	 * @return whether this language server can operate on the given project
	 * @since 0.5
	 */
	public boolean canOperate(IProject project) {
		if (project.equals(this.initialProject) || this.allWatchedProjects.contains(project)) {
			return true;
		}

		return serverDefinition.isSingleton || supportsWorkspaceFolderCapability();
	}

	/**
	 * @return true, if the server supports multi-root workspaces via workspace
	 *         folders
	 * @since 0.6
	 */
	private boolean supportsWorkspaceFolderCapability() {
		if (this.initializeFuture != null) {
			try {
				this.initializeFuture.get(1, TimeUnit.SECONDS);
			} catch (ExecutionException | TimeoutException e) {
				LanguageServerPlugin.logError(e);
			} catch (InterruptedException e) {
				LanguageServerPlugin.logError(e);
				Thread.currentThread().interrupt();
			}
		}
		return initiallySupportsWorkspaceFolders || supportsWorkspaceFolders(serverCapabilities);
	}

	/**
	 * To make public when we support non IFiles
	 *
	 * @return null if not connection has happened, a future that completes when file is initialized otherwise
	 * @noreference internal so far
	 */
	private CompletableFuture<LanguageServer> connect(@NonNull IPath absolutePath, IDocument document) throws IOException {
		final IPath thePath = Path.fromOSString(absolutePath.toFile().getAbsolutePath()); // should be useless

		IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(thePath);
		if (file.exists()) {
			watchProject(file.getProject(), false);
		}

		if (this.connectedDocuments.containsKey(thePath)) {
			return CompletableFuture.completedFuture(languageServer);
		}
		start();
		if (this.initializeFuture == null) {
			return null;
		}
		if (document == null) {
			IFile docFile = (IFile) LSPEclipseUtils.findResourceFor(thePath.toFile().toURI().toString());
			document = LSPEclipseUtils.getDocument(docFile);
		}
		if (document == null) {
			return null;
		}
		final IDocument theDocument = document;
		return initializeFuture.thenComposeAsync(theVoid -> {
			synchronized (connectedDocuments) {
				if (this.connectedDocuments.containsKey(thePath)) {
					return CompletableFuture.completedFuture(null);
				}
				Either<TextDocumentSyncKind, TextDocumentSyncOptions> syncOptions = initializeFuture == null ? null
						: this.serverCapabilities.getTextDocumentSync();
				TextDocumentSyncKind syncKind = null;
				if (syncOptions != null) {
					if (syncOptions.isRight()) {
						syncKind = syncOptions.getRight().getChange();
					} else if (syncOptions.isLeft()) {
						syncKind = syncOptions.getLeft();
					}
				}
				DocumentContentSynchronizer listener = new DocumentContentSynchronizer(this, theDocument, syncKind);
				theDocument.addDocumentListener(listener);
				LanguageServerWrapper.this.connectedDocuments.put(thePath, listener);
				return listener.didOpenFuture;
			}
		}).thenApply(theVoid -> languageServer);
	}

	public void disconnect(IPath path) {
		DocumentContentSynchronizer documentListener = this.connectedDocuments.remove(path);
		if (documentListener != null) {
			documentListener.getDocument().removeDocumentListener(documentListener);
			documentListener.documentClosed();
		}
		if (this.connectedDocuments.isEmpty()) {
			stop();
		}
	}

	public void disconnectContentType(@NonNull IContentType contentType) {
		List<IPath> pathsToDisconnect = new ArrayList<>();
		for (IPath path : connectedDocuments.keySet()) {
			IFile[] foundFiles = ResourcesPlugin.getWorkspace().getRoot()
					.findFilesForLocationURI(path.toFile().toURI());
			if (foundFiles.length != 0
					&& LSPEclipseUtils.getFileContentTypes(foundFiles[0]).stream().anyMatch(contentType::equals)) {
				pathsToDisconnect.add(path);
			}
		}
		for (IPath path : pathsToDisconnect) {
			disconnect(path);
		}
	}

	/**
	 * checks if the wrapper is already connected to the document at the given path
	 *
	 * @noreference test only
	 */
	public boolean isConnectedTo(IPath location) {
		return connectedDocuments.containsKey(location);
	}

	/**
	 * Starts and returns the language server, regardless of if it is initialized.
	 * If not in the UI Thread, will wait to return the initialized server.
	 *
	 * @deprecated use {@link #getInitializedServer()} instead.
	 */
	@Deprecated
	@Nullable
	public LanguageServer getServer() {
		CompletableFuture<LanguageServer> languagServerFuture = getInitializedServer();
		if (Display.getCurrent() != null) { // UI Thread
			return this.languageServer;
		} else {
			return languagServerFuture.join();
		}
	}

	/**
	 * Starts the language server and returns a CompletableFuture waiting for the
	 * server to be initialized. If done in the UI stream, a job will be created
	 * displaying that the server is being initialized
	 */
	@NonNull
	public CompletableFuture<LanguageServer> getInitializedServer() {
		try {
			start();
		} catch (IOException ex) {
			LanguageServerPlugin.logError(ex);
		}
		if (initializeFuture != null && !this.initializeFuture.isDone()) {
			if (Display.getCurrent() != null) { // UI Thread
				Job waitForInitialization = new Job(Messages.initializeLanguageServer_job) {
					@Override
					protected IStatus run(IProgressMonitor monitor) {
						initializeFuture.join();
						return Status.OK_STATUS;
					}
				};
				waitForInitialization.setUser(true);
				waitForInitialization.setSystem(false);
				PlatformUI.getWorkbench().getProgressService().showInDialog(
						PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), waitForInitialization);
			}
			return initializeFuture.thenApply(r -> this.languageServer);
		}
		return CompletableFuture.completedFuture(this.languageServer);
	}

	/**
	 * Warning: this is a long running operation
	 *
	 * @return the server capabilities, or null if initialization job didn't
	 *         complete
	 */
	@Nullable
	public ServerCapabilities getServerCapabilities() {
		try {
			getInitializedServer().get(10, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			LanguageServerPlugin.logError("LanguageServer not initialized after 10s", e); //$NON-NLS-1$
		} catch (ExecutionException e) {
			LanguageServerPlugin.logError(e);
		} catch (CancellationException e) {
			LanguageServerPlugin.logError(e);
		} catch (InterruptedException e) {
			LanguageServerPlugin.logError(e);
			Thread.currentThread().interrupt();
		}

		return this.serverCapabilities;
	}

	/**
	 * @return The language ID that this wrapper is dealing with if defined in the
	 *         content type mapping for the language server
	 */
	@Nullable
	public String getLanguageId(IContentType[] contentTypes) {
		for (IContentType contentType : contentTypes) {
			String languageId = serverDefinition.langugeIdMappings.get(contentType);
			if (languageId != null) {
				return languageId;
			}
		}
		return null;
	}

	void registerCapability(RegistrationParams params) {
		params.getRegistrations().forEach(reg -> {
			if ("workspace/didChangeWorkspaceFolders".equals(reg.getMethod())) { //$NON-NLS-1$
				Assert.isNotNull(serverCapabilities,
						"Dynamic capability registration failed! Server not yet initialized?"); //$NON-NLS-1$
				if (initiallySupportsWorkspaceFolders) {
					// Can treat this as a NOP since nothing can disable it dynamically if it was
					// enabled on initialization.
				} else if (supportsWorkspaceFolders(serverCapabilities)) {
					LanguageServerPlugin.logWarning(
							"Dynamic registration of 'workspace/didChangeWorkspaceFolders' ignored. It was already enabled before", //$NON-NLS-1$
							null);
				} else {
					addRegistration(reg, () -> setWorkspaceFoldersEnablement(false));
					setWorkspaceFoldersEnablement(true);
				}
			} else if ("workspace/executeCommand".equals(reg.getMethod())) { //$NON-NLS-1$
				Gson gson = new Gson(); // TODO? retrieve the GSon used by LS
				ExecuteCommandOptions executeCommandOptions = gson.fromJson((JsonObject) reg.getRegisterOptions(),
						ExecuteCommandOptions.class);
				List<String> newCommands = executeCommandOptions.getCommands();
				if (!newCommands.isEmpty()) {
					addRegistration(reg, () -> unregisterCommands(newCommands));
					registerCommands(newCommands);
				}
			} else if ("textDocument/formatting".equals(reg.getMethod())) { //$NON-NLS-1$
				final Boolean beforeRegistration = serverCapabilities.getDocumentFormattingProvider();
				serverCapabilities.setDocumentFormattingProvider(Boolean.TRUE);
				addRegistration(reg, () -> serverCapabilities.setDocumentFormattingProvider(beforeRegistration));
			} else if ("textDocument/rangeFormatting".equals(reg.getMethod())) { //$NON-NLS-1$
				final Boolean beforeRegistration = serverCapabilities.getDocumentRangeFormattingProvider();
				serverCapabilities.setDocumentRangeFormattingProvider(Boolean.TRUE);
				addRegistration(reg, () -> serverCapabilities.setDocumentRangeFormattingProvider(beforeRegistration));
			} else if ("textDocument/codeAction".equals(reg.getMethod())){ //$NON-NLS-1$
				final Either<Boolean, CodeActionOptions> beforeRegistration = serverCapabilities.getCodeActionProvider();
				serverCapabilities.setCodeActionProvider(Boolean.TRUE);
				addRegistration(reg, () -> serverCapabilities.setCodeActionProvider(beforeRegistration));
			}
		});
	}

	private void addRegistration(@NonNull Registration reg, @NonNull Runnable unregistrationHandler) {
		String regId = reg.getId();
		synchronized (dynamicRegistrations) {
			Assert.isLegal(!dynamicRegistrations.containsKey(regId), "Registration id is not unique"); //$NON-NLS-1$
			dynamicRegistrations.put(regId, unregistrationHandler);
		}
	}

	synchronized void setWorkspaceFoldersEnablement(boolean enable) {
		if (serverCapabilities == null) {
			this.serverCapabilities = new ServerCapabilities();
		}
		WorkspaceServerCapabilities workspace = serverCapabilities.getWorkspace();
		if (workspace == null) {
			workspace = new WorkspaceServerCapabilities();
			serverCapabilities.setWorkspace(workspace);
		}
		WorkspaceFoldersOptions folders = workspace.getWorkspaceFolders();
		if (folders == null) {
			folders = new WorkspaceFoldersOptions();
			workspace.setWorkspaceFolders(folders);
		}
		folders.setSupported(enable);
	}

	synchronized void registerCommands(List<String> newCommands) {
		ServerCapabilities caps = this.getServerCapabilities();
		if (caps != null) {
			ExecuteCommandOptions commandProvider = caps.getExecuteCommandProvider();
			if (commandProvider == null) {
				commandProvider = new ExecuteCommandOptions(new ArrayList<>());
				caps.setExecuteCommandProvider(commandProvider);
			}
			List<String> existingCommands = commandProvider.getCommands();
			for (String newCmd : newCommands) {
				Assert.isLegal(!existingCommands.contains(newCmd), "Command already registered '" + newCmd + "'"); //$NON-NLS-1$ //$NON-NLS-2$
				existingCommands.add(newCmd);
			}
		} else {
			throw new IllegalStateException("Dynamic command registration failed! Server not yet initialized?"); //$NON-NLS-1$
		}
	}

	void unregisterCapability(UnregistrationParams params) {
		params.getUnregisterations().forEach(reg -> {
			String id = reg.getId();
			Runnable unregistrator;
			synchronized (dynamicRegistrations) {
				unregistrator = dynamicRegistrations.get(id);
				dynamicRegistrations.remove(id);
			}
			if (unregistrator != null) {
				unregistrator.run();
			}
		});
	}

	void unregisterCommands(List<String> cmds) {
		ServerCapabilities caps = this.getServerCapabilities();
		if (caps != null) {
			ExecuteCommandOptions commandProvider = caps.getExecuteCommandProvider();
			if (commandProvider != null) {
				List<String> existingCommands = commandProvider.getCommands();
				existingCommands.removeAll(cmds);
			}
		}
	}

	int getVersion(IFile file) {
		if (file != null && file.getLocation() != null) {
			DocumentContentSynchronizer documentContentSynchronizer = connectedDocuments.get(file.getLocation());
			if (documentContentSynchronizer != null) {
				return documentContentSynchronizer.getVersion();
			}
		}
		return -1;
	}

	public boolean canOperate(@NonNull IDocument document) {
		URI documentUri = LSPEclipseUtils.toUri(document);
		if (documentUri == null) {
			return false;
		}
		if (this.isConnectedTo(new Path(documentUri.getPath()))) {
			return true;
		}
		if (this.initialProject == null && this.connectedDocuments.isEmpty()) {
			return true;
		}
		IFile file = LSPEclipseUtils.getFile(document);
		if (file != null && file.exists() && canOperate(file.getProject())) {
			return true;
		}
		return serverDefinition.isSingleton || supportsWorkspaceFolderCapability();
	}

}