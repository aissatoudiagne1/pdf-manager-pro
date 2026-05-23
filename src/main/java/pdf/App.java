package pdf;
import static spark.Spark.*;
import com.google.gson.Gson;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.util.Matrix;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class App {
    static final String UPLOAD_DIR="uploads", OUTPUT_DIR="outputs";
    static final String ADMIN_USER="admin", ADMIN_PASS="admin123";
    static final Gson gson = new Gson();
    static List<Map<String,String>> historique = new ArrayList<>();
    static int totalOps=0, totalUploads=0;

    static Map<String,String> ok(String msg, String f) {
        Map<String,String> m = new HashMap<>();
        m.put("status","ok"); m.put("message",msg);
        if(f!=null) m.put("fichier",f);
        return m;
    }
    static Map<String,String> err(String msg) {
        Map<String,String> m = new HashMap<>();
        m.put("error",msg); return m;
    }
    static void addH(String op, String f, String s) {
        Map<String,String> e = new HashMap<>();
        e.put("operation",op); e.put("fichier",f); e.put("statut",s);
        e.put("date", new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date()));
        historique.add(0,e);
        if(historique.size()>50) historique.remove(historique.size()-1);
        totalOps++;
    }

    public static void main(String[] args) throws Exception {
        new File(UPLOAD_DIR).mkdirs();
        new File(OUTPUT_DIR).mkdirs();
        String pe = System.getenv("PORT");
        if(pe!=null) port(Integer.parseInt(pe)); else port(8080);
        staticFiles.externalLocation("public");

        post("/login",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);
            String u=(String)b.get("username"), p=(String)b.get("password");
            Map<String,String> r=new HashMap<>();
            if(ADMIN_USER.equals(u)&&ADMIN_PASS.equals(p)){req.session(true).attribute("role","admin");r.put("status","ok");r.put("role","admin");}
            else{req.session(true).attribute("role","client");r.put("status","ok");r.put("role","client");}
            res.type("application/json"); return gson.toJson(r);
        });

        post("/logout",(req,res)->{req.session().invalidate();res.type("application/json");return gson.toJson(ok("ok",null));});

        post("/upload",(req,res)->{
            req.attribute("org.eclipse.jetty.multipartConfig",new javax.servlet.MultipartConfigElement("/tmp"));
            try {
                javax.servlet.http.Part part=req.raw().getPart("file");
                String fn=part.getSubmittedFileName();
                String path=UPLOAD_DIR+"/"+fn;
                InputStream is=part.getInputStream();
                Files.copy(is,Paths.get(path),StandardCopyOption.REPLACE_EXISTING);
                is.close(); totalUploads++;
                addH("Upload",fn,"OK");
                Map<String,String> r=new HashMap<>();
                r.put("status","ok");r.put("filename",fn);r.put("path",path);r.put("size",String.valueOf(new File(path).length()));
                res.type("application/json"); return gson.toJson(r);
            } catch(Exception e){res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/creer",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);
            String t=(String)b.get("texte"), s=OUTPUT_DIR+"/"+(String)b.get("sortie");
            try {
                PDDocument doc=new PDDocument();PDPage page=new PDPage();doc.addPage(page);
                PDPageContentStream cs=new PDPageContentStream(doc,page);
                cs.beginText();cs.setFont(PDType1Font.HELVETICA,12);cs.newLineAtOffset(50,700);
                for(String l:t.split("\n")){String safe="";for(char c:l.toCharArray())safe+=(c<128)?c:'?';cs.showText(safe);cs.newLineAtOffset(0,-16);}
                cs.endText();cs.close();doc.save(s);doc.close();
                addH("Creation",s,"OK");res.type("application/json");return gson.toJson(ok("PDF cree",s));
            }catch(Exception e){addH("Creation",s,"ERR");res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/extraire-texte",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);String f=(String)b.get("fichier");
            try {
                PDDocument doc=PDDocument.load(new File(f));int pg=doc.getNumberOfPages();
                String tx=new PDFTextStripper().getText(doc);doc.close();addH("Extraction",f,"OK");
                Map<String,String> r=new HashMap<>();r.put("status","ok");r.put("texte",tx);r.put("pages",String.valueOf(pg));
                res.type("application/json");return gson.toJson(r);
            }catch(Exception e){addH("Extraction",f,"ERR");res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/fusionner",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);
            String f1=(String)b.get("fichier1"),f2=(String)b.get("fichier2"),s=OUTPUT_DIR+"/"+(String)b.get("sortie");
            try {
                PDFMergerUtility m=new PDFMergerUtility();m.addSource(new File(f1));m.addSource(new File(f2));
                m.setDestinationFileName(s);m.mergeDocuments(null);
                addH("Fusion",s,"OK");res.type("application/json");return gson.toJson(ok("Fusion reussie",s));
            }catch(Exception e){addH("Fusion",s,"ERR");res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/decouper",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);
            String f=(String)b.get("fichier"),s=OUTPUT_DIR+"/"+(String)b.get("sortie");
            int d=((Double)b.get("debut")).intValue(),fi=((Double)b.get("fin")).intValue();
            try {
                PDDocument doc=PDDocument.load(new File(f));PDDocument r=new PDDocument();
                for(int i=d-1;i<fi&&i<doc.getNumberOfPages();i++) r.addPage(doc.getPage(i));
                r.save(s);r.close();doc.close();
                addH("Decoupage",s,"OK");res.type("application/json");return gson.toJson(ok("Decoupage reussi",s));
            }catch(Exception e){addH("Decoupage",s,"ERR");res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/vers-image",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);
            String f=(String)b.get("fichier"),ds=OUTPUT_DIR+"/images";
            try {
                PDDocument doc=PDDocument.load(new File(f));new File(ds).mkdirs();
                PDFRenderer rend=new PDFRenderer(doc);int n=doc.getNumberOfPages();
                for(int i=0;i<n;i++){BufferedImage img=rend.renderImageWithDPI(i,150);ImageIO.write(img,"PNG",new File(ds+"/page_"+(i+1)+".png"));}
                doc.close();addH("Conversion",f,"OK");
                res.type("application/json");return gson.toJson(ok(n+" image(s) generee(s)",null));
            }catch(Exception e){addH("Conversion",f,"ERR");res.status(500);return gson.toJson(err(e.getMessage()));}
        });
        post("/supprimer-page",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);
            String f=(String)b.get("fichier"),s=OUTPUT_DIR+"/"+(String)b.get("sortie");
            int page=((Double)b.get("page")).intValue();
            try {
                PDDocument doc=PDDocument.load(new File(f));
                if(page<1||page>doc.getNumberOfPages()) throw new Exception("Page invalide: "+page);
                doc.removePage(page-1);doc.save(s);doc.close();
                addH("Suppression page",s,"OK");res.type("application/json");return gson.toJson(ok("Page "+page+" supprimee",s));
            }catch(Exception e){addH("Suppression page",s,"ERR");res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/mot-de-passe",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);
            String f=(String)b.get("fichier"),mdp=(String)b.get("motdepasse"),s=OUTPUT_DIR+"/"+(String)b.get("sortie");
            try {
                PDDocument doc=PDDocument.load(new File(f));
                AccessPermission ap=new AccessPermission();
                StandardProtectionPolicy policy=new StandardProtectionPolicy(mdp,mdp,ap);
                policy.setEncryptionKeyLength(128);doc.protect(policy);doc.save(s);doc.close();
                addH("Mot de passe",s,"OK");res.type("application/json");return gson.toJson(ok("PDF protege",s));
            }catch(Exception e){addH("Mot de passe",s,"ERR");res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/rotation",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);
            String f=(String)b.get("fichier"),s=OUTPUT_DIR+"/"+(String)b.get("sortie");
            int angle=((Double)b.get("angle")).intValue();
            try {
                PDDocument doc=PDDocument.load(new File(f));
                for(PDPage pg:doc.getPages()) pg.setRotation((pg.getRotation()+angle)%360);
                doc.save(s);doc.close();
                addH("Rotation",s,"OK");res.type("application/json");return gson.toJson(ok("Rotation "+angle+" OK",s));
            }catch(Exception e){addH("Rotation",s,"ERR");res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/filigrane",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);
            String f=(String)b.get("fichier"),s=OUTPUT_DIR+"/"+(String)b.get("sortie"),texte=(String)b.get("texte");
            try {
                PDDocument doc=PDDocument.load(new File(f));
                for(PDPage pg:doc.getPages()){
                    PDRectangle rect=pg.getMediaBox();
                    PDPageContentStream cs=new PDPageContentStream(doc,pg,PDPageContentStream.AppendMode.APPEND,true,true);
                    cs.setFont(PDType1Font.HELVETICA_BOLD,40);
                    cs.beginText();
                    cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(45),rect.getWidth()/2-80,rect.getHeight()/2-20));
                    String safe="";for(char c:texte.toCharArray())safe+=(c<128)?c:'?';
                    cs.showText(safe);cs.endText();cs.close();
                }
                doc.save(s);doc.close();
                addH("Filigrane",s,"OK");res.type("application/json");return gson.toJson(ok("Filigrane ajoute",s));
            }catch(Exception e){addH("Filigrane",s,"ERR");res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/numerotation",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);
            String f=(String)b.get("fichier"),s=OUTPUT_DIR+"/"+(String)b.get("sortie");
            try {
                PDDocument doc=PDDocument.load(new File(f));int num=1;
                for(PDPage pg:doc.getPages()){
                    PDRectangle rect=pg.getMediaBox();
                    PDPageContentStream cs=new PDPageContentStream(doc,pg,PDPageContentStream.AppendMode.APPEND,true,true);
                    cs.setFont(PDType1Font.HELVETICA,10);cs.beginText();
                    cs.newLineAtOffset(rect.getWidth()/2-10,20);
                    cs.showText("- "+num+" -");cs.endText();cs.close();num++;
                }
                doc.save(s);doc.close();
                addH("Numerotation",s,"OK");res.type("application/json");return gson.toJson(ok("Pages numerotees",s));
            }catch(Exception e){addH("Numerotation",s,"ERR");res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/compresser",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);
            String f=(String)b.get("fichier"),s=OUTPUT_DIR+"/"+(String)b.get("sortie");
            try {
                PDDocument doc=PDDocument.load(new File(f));
                doc.save(s);doc.close();
                long avant=new File(f).length(),apres=new File(s).length();
                addH("Compression",s,"OK");res.type("application/json");
                return gson.toJson(ok("Compression OK. "+avant/1024+"KB -> "+apres/1024+"KB",s));
            }catch(Exception e){addH("Compression",s,"ERR");res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/inverser",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);
            String f=(String)b.get("fichier"),s=OUTPUT_DIR+"/"+(String)b.get("sortie");
            try {
                PDDocument doc=PDDocument.load(new File(f));PDDocument inv=new PDDocument();
                int n=doc.getNumberOfPages();
                for(int i=n-1;i>=0;i--) inv.addPage(doc.getPage(i));
                inv.save(s);inv.close();doc.close();
                addH("Inversion",s,"OK");res.type("application/json");return gson.toJson(ok("Pages inversees ("+n+")",s));
            }catch(Exception e){res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/metadonnees",(req,res)->{
            Map b=gson.fromJson(req.body(),Map.class);String f=(String)b.get("fichier");
            try {
                PDDocument doc=PDDocument.load(new File(f));PDDocumentInformation info=doc.getDocumentInformation();
                Map<String,String> r=new HashMap<>();r.put("status","ok");
                r.put("titre",info.getTitle()!=null?info.getTitle():"N/A");
                r.put("auteur",info.getAuthor()!=null?info.getAuthor():"N/A");
                r.put("sujet",info.getSubject()!=null?info.getSubject():"N/A");
                r.put("createur",info.getCreator()!=null?info.getCreator():"N/A");
                r.put("pages",String.valueOf(doc.getNumberOfPages()));
                r.put("taille",new File(f).length()+" octets");
                doc.close();res.type("application/json");return gson.toJson(r);
            }catch(Exception e){res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        post("/word-vers-pdf",(req,res)->{
            req.attribute("org.eclipse.jetty.multipartConfig",new javax.servlet.MultipartConfigElement("/tmp",10*1024*1024,10*1024*1024,0));
            try {
                javax.servlet.http.Part part=req.raw().getPart("fichier");
                String fn=part.getSubmittedFileName();
                String docPath=UPLOAD_DIR+"/"+fn;
                InputStream is=part.getInputStream();
                Files.copy(is,Paths.get(docPath),StandardCopyOption.REPLACE_EXISTING);is.close();
                String pdfName=fn.replaceAll("\\.(docx?|txt)$","")+".pdf";
                String pdfPath=OUTPUT_DIR+"/"+pdfName;

                // Extraire le texte selon le type de fichier
                String contenu="";
                if(fn.endsWith(".docx")){
                    // Lire fichier .docx avec Apache POI
                    XWPFDocument docx=new XWPFDocument(new FileInputStream(docPath));
                    StringBuilder sb=new StringBuilder();
                    for(XWPFParagraph p:docx.getParagraphs()){
                        sb.append(p.getText()).append("\n");
                    }
                    docx.close();
                    contenu=sb.toString();
                } else if(fn.endsWith(".doc")){
                    // Lire fichier .doc avec Apache POI
                    HWPFDocument doc2=new HWPFDocument(new FileInputStream(docPath));
                    WordExtractor ex=new WordExtractor(doc2);
                    contenu=ex.getText();
                    ex.close();doc2.close();
                } else {
                    // Fichier texte simple
                    contenu=new String(Files.readAllBytes(Paths.get(docPath)));
                }

                // Créer le PDF
                PDDocument doc=new PDDocument();
                String[] lignes=contenu.split("\n");int idx=0;
                while(idx<lignes.length){
                    PDPage page=new PDPage();doc.addPage(page);
                    PDPageContentStream cs=new PDPageContentStream(doc,page);
                    cs.setFont(PDType1Font.HELVETICA,11);cs.beginText();cs.newLineAtOffset(50,750);
                    for(int i=0;i<40&&idx<lignes.length;i++,idx++){
                        String safe="";
                        for(char c:lignes[idx].toCharArray()){
                            if(c>=32&&c<127) safe+=c;
                            else if(c==9) safe+="    ";
                        }
                        cs.showText(safe);cs.newLineAtOffset(0,-18);
                    }
                    cs.endText();cs.close();
                }
                doc.save(pdfPath);doc.close();
                addH("Word vers PDF",pdfName,"OK");
                res.type("application/json");return gson.toJson(ok("Converti en PDF",pdfPath));
            }catch(Exception e){res.status(500);return gson.toJson(err(e.getMessage()));}
        });
        get("/download/:fn",(req,res)->{
            String fn=req.params("fn");File file=new File(OUTPUT_DIR+"/"+fn);
            if(!file.exists()){res.status(404);return "404";}
            res.header("Content-Disposition","attachment; filename="+fn);
            res.type("application/octet-stream");
            OutputStream os=res.raw().getOutputStream();
            FileInputStream fis=new FileInputStream(file);
            byte[] buf=new byte[4096];int nb;
            while((nb=fis.read(buf))!=-1)os.write(buf,0,nb);
            fis.close();os.flush();return null;
        });

        get("/fichiers",(req,res)->{
            File dir=new File(UPLOAD_DIR);
            File[] files=dir.listFiles((d,n)->n.endsWith(".pdf")||n.endsWith(".docx")||n.endsWith(".txt"));
            List<Map<String,String>> result=new ArrayList<>();
            if(files!=null)for(File f:files){Map<String,String> m=new HashMap<>();m.put("nom",f.getName());m.put("taille",String.valueOf(f.length()));m.put("path",UPLOAD_DIR+"/"+f.getName());result.add(m);}
            res.type("application/json");return gson.toJson(result);
        });

        get("/outputs",(req,res)->{
            File dir=new File(OUTPUT_DIR);
            File[] files=dir.listFiles((d,n)->n.endsWith(".pdf"));
            List<Map<String,String>> r=new ArrayList<>();
            if(files!=null)for(File f:files){Map<String,String> m=new HashMap<>();m.put("nom",f.getName());m.put("taille",String.valueOf(f.length()));r.add(m);}
            res.type("application/json");return gson.toJson(r);
        });

        get("/admin/stats",(req,res)->{
            Map<String,Object> s=new HashMap<>();
            s.put("totalUploads",totalUploads);s.put("totalOps",totalOps);s.put("historique",historique);
            res.type("application/json");return gson.toJson(s);
        });

        delete("/admin/fichier/:nom",(req,res)->{
            new File(UPLOAD_DIR+"/"+req.params("nom")).delete();
            res.type("application/json");return gson.toJson(ok("Supprime",null));
        });

        post("/image-vers-pdf",(req,res)->{
            req.attribute("org.eclipse.jetty.multipartConfig",new javax.servlet.MultipartConfigElement("/tmp",10*1024*1024,10*1024*1024,0));
            try {
                javax.servlet.http.Part part=req.raw().getPart("image");
                String filename=part.getSubmittedFileName();
                String imgPath=UPLOAD_DIR+"/"+filename;
                InputStream is=part.getInputStream();
                Files.copy(is,Paths.get(imgPath),StandardCopyOption.REPLACE_EXISTING);is.close();
                String pdfName=filename.contains(".")?filename.substring(0,filename.lastIndexOf("."))+".pdf":filename+".pdf";
                String pdfPath=OUTPUT_DIR+"/"+pdfName;
                BufferedImage img=ImageIO.read(new File(imgPath));
                PDDocument doc=new PDDocument();
                PDRectangle rect=new PDRectangle(img.getWidth(),img.getHeight());
                PDPage page=new PDPage(rect);doc.addPage(page);
                PDImageXObject pdImage=PDImageXObject.createFromFile(imgPath,doc);
                PDPageContentStream cs=new PDPageContentStream(doc,page);
                cs.drawImage(pdImage,0,0,img.getWidth(),img.getHeight());
                cs.close();doc.save(pdfPath);doc.close();
                addH("Scan image",pdfName,"OK");res.type("application/json");return gson.toJson(ok("Image convertie",pdfPath));
            }catch(Exception e){res.status(500);return gson.toJson(err(e.getMessage()));}
        });

        System.out.println("=== PDF Manager Pro - http://localhost:8080 ===");
        System.out.println("Fonctionnalites: fusion, decoupage, suppression, rotation,");
        System.out.println("filigrane, numerotation, compression, word->pdf, metadonnees, inversion");
    }
}
